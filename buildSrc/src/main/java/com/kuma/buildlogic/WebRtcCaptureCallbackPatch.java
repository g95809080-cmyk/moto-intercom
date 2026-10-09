package com.kuma.buildlogic;

import com.android.build.api.instrumentation.AsmClassVisitorFactory;
import com.android.build.api.instrumentation.ClassContext;
import com.android.build.api.instrumentation.ClassData;
import com.android.build.api.instrumentation.InstrumentationParameters;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Wires the unused capture callback in the pinned stream-webrtc-android 1.3.9 Java layer.
 * Native methods, native libraries and their ABI remain unchanged. See the KUM-66 design.
 */
public abstract class WebRtcCaptureCallbackPatch
        implements AsmClassVisitorFactory<InstrumentationParameters.None> {
    private static final String RECORD = "org/webrtc/audio/WebRtcAudioRecord";
    private static final String BUILDER = "org/webrtc/audio/JavaAudioDeviceModule$Builder";
    private static final String THREAD = RECORD + "$AudioRecordThread";
    private static final String CALLBACK = "Lorg/webrtc/audio/AudioRecordDataCallback;";
    private static final String FIELD = "motoCaptureCallback";

    @Override public boolean isInstrumentable(ClassData data) {
        String name = data.getClassName().replace('.', '/');
        return name.equals(RECORD) || name.equals(BUILDER) || name.equals(THREAD);
    }

    @Override public ClassVisitor createClassVisitor(ClassContext context, ClassVisitor next) {
        String type = context.getCurrentClassData().getClassName().replace('.', '/');
        return new ClassVisitor(Opcodes.ASM9, next) {
            int sites;
            int fields;
            int nativeDeclarations;
            int constructors;
            int stops;
            int stopThreads;
            @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                if (name.startsWith("moto")) throw new IllegalStateException("Duplicate WebRTC capture patch");
                if (type.equals(RECORD) && ((name.equals("byteBuffer") && descriptor.equals("Ljava/nio/ByteBuffer;"))
                        || (name.equals("audioRecord") && descriptor.equals("Landroid/media/AudioRecord;"))
                        || (name.equals("audioThread") && descriptor.equals("L" + THREAD + ";")))) fields++;
                if (type.equals(RECORD) && name.equals("audioThread")) access |= Opcodes.ACC_VOLATILE;
                if (type.equals(THREAD) && ((name.equals("keepAlive") && descriptor.equals("Z") && (access & Opcodes.ACC_VOLATILE) != 0)
                        || (name.equals("this$0") && descriptor.equals("L" + RECORD + ";")))) fields++;
                if (type.equals(BUILDER) && name.equals("audioRecordDataCallback") && descriptor.equals(CALLBACK)) fields++;
                return super.visitField(access, name, descriptor, signature, value);
            }
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                if (name.startsWith("moto")) throw new IllegalStateException("Duplicate WebRTC capture patch");
                if (type.equals(RECORD) && name.equals("nativeDataIsRecorded") && descriptor.equals("(JIJ)V")
                        && (access & Opcodes.ACC_NATIVE) != 0) nativeDeclarations++;
                if (type.equals(THREAD) && name.equals("stopThread") && descriptor.equals("()V")) {
                    // Stop revokes the same short JNI borrow. The callback and SDK join never hold this monitor.
                    access |= Opcodes.ACC_SYNCHRONIZED;
                    stopThreads++;
                }
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                boolean builder = type.equals(BUILDER) && name.equals("createAudioDeviceModule")
                        && descriptor.equals("()Lorg/webrtc/audio/JavaAudioDeviceModule;");
                boolean thread = type.equals(THREAD) && name.equals("run") && descriptor.equals("()V");
                boolean constructor = type.equals(THREAD) && name.equals("<init>")
                        && descriptor.equals("(L" + RECORD + ";Ljava/lang/String;)V");
                if (!builder && !thread && !constructor) return delegate;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override public void visitFieldInsn(int opcode, String owner, String field, String fieldType) {
                        if (thread && opcode == Opcodes.GETFIELD && owner.equals(RECORD)
                                && (field.equals("audioRecord") || field.equals("byteBuffer"))) {
                            // A timed-out old read must never clear, timestamp, sample or stop a replacement's buffers.
                            super.visitInsn(Opcodes.POP);
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(Opcodes.GETFIELD, THREAD,
                                    field.equals("audioRecord") ? "motoOwnedRecord" : "motoOwnedBuffer", fieldType);
                            return;
                        }
                        super.visitFieldInsn(opcode, owner, field, fieldType);
                    }
                    @Override public void visitMethodInsn(int opcode, String owner, String method,
                            String desc, boolean isInterface) {
                        if (thread && owner.equals(RECORD) && method.equals("nativeDataIsRecorded")
                                && desc.equals("(JIJ)V")) {
                            // Save the pinned invocation operands in a reserved, checked local range.
                            super.visitVarInsn(Opcodes.LSTORE, 32);
                            super.visitVarInsn(Opcodes.ISTORE, 34);
                            super.visitVarInsn(Opcodes.LSTORE, 35);
                            super.visitVarInsn(Opcodes.ASTORE, 37);
                            Label alive = new Label();
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(Opcodes.GETFIELD, THREAD, "keepAlive", "Z");
                            super.visitJumpInsn(Opcodes.IFNE, alive);
                            super.visitInsn(Opcodes.RETURN);
                            super.visitLabel(alive);
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(Opcodes.GETFIELD, THREAD, "this$0", "L" + RECORD + ";");
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(Opcodes.GETFIELD, THREAD, "motoOwnedRecord", "Landroid/media/AudioRecord;");
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(Opcodes.GETFIELD, THREAD, "motoOwnedBuffer", "Ljava/nio/ByteBuffer;");
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RECORD, "motoBeforeNativeRecord",
                                    "(Landroid/media/AudioRecord;Ljava/nio/ByteBuffer;)V", false);
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitVarInsn(Opcodes.ALOAD, 37);
                            super.visitVarInsn(Opcodes.LLOAD, 35);
                            super.visitVarInsn(Opcodes.ILOAD, 34);
                            super.visitVarInsn(Opcodes.LLOAD, 32);
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, THREAD, "motoRecordIfCurrent", "(L" + RECORD + ";JIJ)Z", false);
                            Label accepted = new Label();
                            super.visitJumpInsn(Opcodes.IFNE, accepted);
                            super.visitInsn(Opcodes.RETURN); // Never execute a retired producer's shared SDK tail.
                            super.visitLabel(accepted);
                            sites++;
                            return;
                        }
                        if (thread && owner.equals("android/media/AudioRecord") && method.equals("stop") && desc.equals("()V")) {
                            super.visitVarInsn(Opcodes.ASTORE, 38);
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitVarInsn(Opcodes.ALOAD, 38);
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, THREAD, "motoStopIfCurrent", "(Landroid/media/AudioRecord;)Z", false);
                            Label accepted = new Label();
                            super.visitJumpInsn(Opcodes.IFNE, accepted);
                            super.visitInsn(Opcodes.RETURN);
                            super.visitLabel(accepted);
                            stops++;
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, method, desc, isInterface);
                        if (constructor && opcode == Opcodes.INVOKESPECIAL && owner.equals("java/lang/Thread")
                                && method.equals("<init>") && desc.equals("(Ljava/lang/String;)V")) {
                            for (String field : new String[]{"audioRecord", "byteBuffer"}) {
                                String fieldType = field.equals("audioRecord") ? "Landroid/media/AudioRecord;" : "Ljava/nio/ByteBuffer;";
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitFieldInsn(Opcodes.GETFIELD, RECORD, field, fieldType);
                                super.visitFieldInsn(Opcodes.PUTFIELD, THREAD, field.equals("audioRecord") ? "motoOwnedRecord" : "motoOwnedBuffer", fieldType);
                            }
                            constructors++;
                        }
                        if (builder && opcode == Opcodes.INVOKESPECIAL && owner.equals(RECORD)
                                && method.equals("<init>") && desc.equals("(Landroid/content/Context;Ljava/util/concurrent/ScheduledExecutorService;Landroid/media/AudioManager;IILorg/webrtc/audio/JavaAudioDeviceModule$AudioRecordErrorCallback;Lorg/webrtc/audio/JavaAudioDeviceModule$AudioRecordStateCallback;Lorg/webrtc/audio/JavaAudioDeviceModule$SamplesReadyCallback;ZZ)V")) {
                            // The constructor's DUP leaves the new recorder on the stack.
                            super.visitInsn(Opcodes.DUP);
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(Opcodes.GETFIELD, BUILDER, "audioRecordDataCallback", CALLBACK);
                            super.visitFieldInsn(Opcodes.PUTFIELD, RECORD, FIELD, CALLBACK);
                            sites++;
                        }
                    }
                    @Override public void visitMaxs(int stack, int locals) {
                        if (thread && locals > 32) throw new IllegalStateException("Unsupported WebRTC thread local layout");
                        super.visitMaxs(thread ? Math.max(stack, 7) : Math.max(stack, 3), thread ? 39 : locals);
                    }
                };
            }

            @Override public void visitEnd() {
                if (type.equals(RECORD) && (fields != 3 || nativeDeclarations != 1)
                        || type.equals(BUILDER) && fields != 1
                        || type.equals(THREAD) && (fields != 2 || constructors != 1 || stops != 1 || stopThreads != 1)) {
                    throw new IllegalStateException("Unsupported WebRTC recorder layout in " + type);
                }
                if (!type.equals(RECORD) && sites != 1) {
                    throw new IllegalStateException("WebRTC 1.3.9 capture patch expected one site in " + type + ", found " + sites);
                }
                if (type.equals(RECORD)) {
                    super.visitField(Opcodes.ACC_PUBLIC, FIELD, CALLBACK, null, null).visitEnd();
                    MethodVisitor method = super.visitMethod(Opcodes.ACC_PUBLIC, "motoBeforeNativeRecord",
                            "(Landroid/media/AudioRecord;Ljava/nio/ByteBuffer;)V", null, null);
                    method.visitCode();
                    Label done = new Label();
                    method.visitVarInsn(Opcodes.ALOAD, 0);
                    method.visitFieldInsn(Opcodes.GETFIELD, RECORD, FIELD, CALLBACK);
                    method.visitJumpInsn(Opcodes.IFNULL, done);
                    method.visitVarInsn(Opcodes.ALOAD, 0);
                    method.visitFieldInsn(Opcodes.GETFIELD, RECORD, FIELD, CALLBACK);
                    for (String getter : new String[]{"getAudioFormat", "getChannelCount", "getSampleRate"}) {
                        method.visitVarInsn(Opcodes.ALOAD, 1);
                        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "android/media/AudioRecord", getter, "()I", false);
                    }
                    method.visitVarInsn(Opcodes.ALOAD, 2);
                    method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/webrtc/audio/AudioRecordDataCallback",
                            "onAudioDataRecorded", "(IIILjava/nio/ByteBuffer;)V", true);
                    method.visitLabel(done);
                    method.visitInsn(Opcodes.RETURN);
                    method.visitMaxs(5, 3);
                    method.visitEnd();
                }
                if (type.equals(THREAD)) {
                    super.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "motoOwnedRecord", "Landroid/media/AudioRecord;", null, null).visitEnd();
                    super.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "motoOwnedBuffer", "Ljava/nio/ByteBuffer;", null, null).visitEnd();
                    MethodVisitor record = super.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNCHRONIZED,
                            "motoRecordIfCurrent", "(L" + RECORD + ";JIJ)Z", null, null);
                    record.visitCode();
                    Label retired = new Label();
                    record.visitVarInsn(Opcodes.ALOAD, 0);
                    record.visitFieldInsn(Opcodes.GETFIELD, THREAD, "keepAlive", "Z");
                    record.visitJumpInsn(Opcodes.IFEQ, retired);
                    ownerGuard(record, retired);
                    record.visitVarInsn(Opcodes.ALOAD, 1);
                    record.visitVarInsn(Opcodes.ALOAD, 0);
                    record.visitFieldInsn(Opcodes.GETFIELD, THREAD, "this$0", "L" + RECORD + ";");
                    record.visitJumpInsn(Opcodes.IF_ACMPNE, retired);
                    record.visitVarInsn(Opcodes.ALOAD, 1);
                    record.visitVarInsn(Opcodes.LLOAD, 2);
                    record.visitVarInsn(Opcodes.ILOAD, 4);
                    record.visitVarInsn(Opcodes.LLOAD, 5);
                    record.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RECORD, "nativeDataIsRecorded", "(JIJ)V", false);
                    record.visitInsn(Opcodes.ICONST_1);
                    record.visitInsn(Opcodes.IRETURN);
                    record.visitLabel(retired);
                    record.visitInsn(Opcodes.ICONST_0);
                    record.visitInsn(Opcodes.IRETURN);
                    record.visitMaxs(6, 7);
                    record.visitEnd();

                    MethodVisitor stop = super.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNCHRONIZED,
                            "motoStopIfCurrent", "(Landroid/media/AudioRecord;)Z", null, null);
                    stop.visitCode();
                    Label replaced = new Label();
                    ownerGuard(stop, replaced);
                    stop.visitVarInsn(Opcodes.ALOAD, 1);
                    stop.visitVarInsn(Opcodes.ALOAD, 0);
                    stop.visitFieldInsn(Opcodes.GETFIELD, THREAD, "motoOwnedRecord", "Landroid/media/AudioRecord;");
                    stop.visitJumpInsn(Opcodes.IF_ACMPNE, replaced);
                    stop.visitVarInsn(Opcodes.ALOAD, 1);
                    stop.visitVarInsn(Opcodes.ALOAD, 0);
                    stop.visitFieldInsn(Opcodes.GETFIELD, THREAD, "this$0", "L" + RECORD + ";");
                    stop.visitFieldInsn(Opcodes.GETFIELD, RECORD, "audioRecord", "Landroid/media/AudioRecord;");
                    stop.visitJumpInsn(Opcodes.IF_ACMPNE, replaced);
                    stop.visitVarInsn(Opcodes.ALOAD, 1);
                    stop.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "android/media/AudioRecord", "stop", "()V", false);
                    stop.visitInsn(Opcodes.ICONST_1);
                    stop.visitInsn(Opcodes.IRETURN);
                    stop.visitLabel(replaced);
                    stop.visitInsn(Opcodes.ICONST_0);
                    stop.visitInsn(Opcodes.IRETURN);
                    stop.visitMaxs(2, 2);
                    stop.visitEnd();
                }
                super.visitEnd();
            }
        };
    }

    private static void ownerGuard(MethodVisitor method, Label rejected) {
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, THREAD, "this$0", "L" + RECORD + ";");
        method.visitFieldInsn(Opcodes.GETFIELD, RECORD, "audioThread", "L" + THREAD + ";");
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitJumpInsn(Opcodes.IF_ACMPNE, rejected);
    }
}
