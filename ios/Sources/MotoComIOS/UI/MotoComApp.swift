import SwiftUI

@MainActor
public struct MotoComRootView: View {
    @StateObject private var session: SessionCoordinator
    @Environment(\.scenePhase) private var scenePhase

    public init(session: SessionCoordinator? = nil) {
        _session = StateObject(wrappedValue: session ?? SessionCoordinator())
    }

    public var body: some View {
        NavigationStack {
            List {
                Section("当前状态") {
                    Label(session.phase.rawValue, systemImage: phaseIcon)
                        .accessibilityValue(session.statusMessage)
                    Text(session.statusMessage)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Section("设备") {
                    if let identity = session.identity {
                        LabeledContent("平台", value: "iOS")
                        LabeledContent("设备", value: identity.deviceName)
                        LabeledContent("短码", value: ShortCode.derive(from: identity.deviceID))
                    } else {
                        Text("正在生成稳定设备身份…")
                    }
                }

                Section("附近设备") {
                    if session.nearbyPeers.isEmpty {
                        Text("开始发现后会显示附近的 Android/iPhone")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(session.nearbyPeers) { peer in
                            HStack {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(peer.nickname)
                                    Text("\(peer.capabilities.platform.rawValue) · \(peer.deviceName)")
                                        .font(.footnote)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                #if canImport(Network)
                                Button("连接") {
                                    session.connect(to: peer)
                                }
                                .buttonStyle(.borderedProminent)
                                #endif
                            }
                        }
                    }
                }

                Section("操作") {
                    Button("开始发现附近设备", systemImage: "dot.radiowaves.left.and.right") {
                        Task {
                            guard await session.requestMicrophonePermission() else { return }
                            session.startDiscovery()
                        }
                    }
                    .disabled(session.identity == nil)

                    Button("使用 iPhone 作为主机", systemImage: "personalhotspot") {
                        session.prepareIOSHost()
                    }
                    .disabled(session.identity == nil || session.phase == .connected)

                    if session.phase == .manualActionRequired {
                        Button("我已完成网络设置，继续发现", systemImage: "arrow.clockwise") {
                            session.finishManualNetworkSetup()
                        }
                    }

                    Button("接受连接", systemImage: "checkmark.circle") {
                        session.acceptIncoming()
                    }
                    .disabled(session.phase != .awaitingConfirmation)

                    Button("结束对讲", systemImage: "phone.down", role: .destructive) {
                        session.stop()
                    }
                    .disabled(session.phase == .idle || session.phase == .offline)

                    if session.phase == .manualActionRequired {
                        Button("打开系统网络设置", systemImage: "gear") {
                            session.openSystemNetworkSettings()
                        }
                    }
                }

                Section("已配对") {
                    if session.pairings.isEmpty {
                        Text("连接成功且 Audio Ready 后会出现在这里")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(session.pairings) { pairing in
                            VStack(alignment: .leading, spacing: 4) {
                                Text(pairing.localAlias.isEmpty ? pairing.remoteNickname : pairing.localAlias)
                                Text("\(pairing.deviceName) · \(pairing.shortCode)")
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
            .navigationTitle("MotoCom")
            .onChange(of: scenePhase) { newPhase in
                if newPhase == .active, session.phase == .recovering {
                    session.recover()
                }
            }
        }
    }

    private var phaseIcon: String {
        switch session.phase {
        case .connected, .audioReady: return "checkmark.circle.fill"
        case .failed, .permissionBlocked: return "exclamationmark.triangle.fill"
        case .offline, .idle: return "circle"
        default: return "ellipsis.circle"
        }
    }
}
