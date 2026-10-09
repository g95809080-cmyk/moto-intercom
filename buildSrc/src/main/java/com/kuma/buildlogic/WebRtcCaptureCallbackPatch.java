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
            @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                if (name.equals(FIELD)) throw new IllegalStateException("Duplicate WebRTC capture patch");
                if (type.equals(RECORD) && ((name.equals("byteBuffer") && descriptor.equals("Ljava/nio/ByteBuffer;"))
                        || (name.equals("audioRecord") && descriptor.equals("Landroid/media/AudioRecord;")))) fields++;
                if (type.equals(BUILDER) && name.equals("audioRecordDataCallback") && descriptor.equals(CALLBACK)) fields++;
                return super.visitField(access, name, descriptor, signature, value);
            }
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                if (name.equals("motoBeforeNativeRecord")) throw new IllegalStateException("Duplicate WebRTC capture patch");
                if (type.equals(RECORD) && name.equals("nativeDataIsRecorded") && descriptor.equals("(JIJ)V")
                        && (access & Opcodes.ACC_NATIVE) != 0) nativeDeclarations++;
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                boolean builder = type.equals(BUILDER) && name.equals("createAudioDeviceModule")
                        && descriptor.equals("()Lorg/webrtc/audio/JavaAudioDeviceModule;");
                boolean thread = type.equals(THREAD) && name.equals("run") && descriptor.equals("()V");
                if (!builder && !thread) return delegate;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override public void visitMethodInsn(int opcode, String owner, String method,
                            String desc, boolean isInterface) {
                        if (thread && owner.equals(RECORD) && method.equals("nativeDataIsRecorded")
                                && desc.equals("(JIJ)V")) {
                            // Preserve the original invocation stack; run the callback immediately before JNI.
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(Opcodes.GETFIELD, THREAD, "this$0", "L" + RECORD + ";");
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RECORD, "motoBeforeNativeRecord", "()V", false);
                            sites++;
                        }
                        super.visitMethodInsn(opcode, owner, method, desc, isInterface);
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
                };
            }

            @Override public void visitEnd() {
                if (type.equals(RECORD) && (fields != 2 || nativeDeclarations != 1)
                        || type.equals(BUILDER) && fields != 1) {
                    throw new IllegalStateException("Unsupported WebRTC recorder layout in " + type);
                }
                if (!type.equals(RECORD) && sites != 1) {
                    throw new IllegalStateException("WebRTC 1.3.9 capture patch expected one site in " + type + ", found " + sites);
                }
                if (type.equals(RECORD)) {
                    super.visitField(Opcodes.ACC_PUBLIC, FIELD, CALLBACK, null, null).visitEnd();
                    MethodVisitor method = super.visitMethod(Opcodes.ACC_PUBLIC, "motoBeforeNativeRecord", "()V", null, null);
                    method.visitCode();
                    Label done = new Label();
                    method.visitVarInsn(Opcodes.ALOAD, 0);
                    method.visitFieldInsn(Opcodes.GETFIELD, RECORD, FIELD, CALLBACK);
                    method.visitJumpInsn(Opcodes.IFNULL, done);
                    method.visitVarInsn(Opcodes.ALOAD, 0);
                    method.visitFieldInsn(Opcodes.GETFIELD, RECORD, FIELD, CALLBACK);
                    for (String getter : new String[]{"getAudioFormat", "getChannelCount", "getSampleRate"}) {
                        method.visitVarInsn(Opcodes.ALOAD, 0);
                        method.visitFieldInsn(Opcodes.GETFIELD, RECORD, "audioRecord", "Landroid/media/AudioRecord;");
                        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "android/media/AudioRecord", getter, "()I", false);
                    }
                    method.visitVarInsn(Opcodes.ALOAD, 0);
                    method.visitFieldInsn(Opcodes.GETFIELD, RECORD, "byteBuffer", "Ljava/nio/ByteBuffer;");
                    method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/webrtc/audio/AudioRecordDataCallback",
                            "onAudioDataRecorded", "(IIILjava/nio/ByteBuffer;)V", true);
                    method.visitLabel(done);
                    method.visitInsn(Opcodes.RETURN);
                    method.visitMaxs(5, 1);
                    method.visitEnd();
                }
                super.visitEnd();
            }
        };
    }
}
