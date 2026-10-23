import SwiftUI

// MARK: - Root
struct RootView: View {
    @StateObject private var mesh = MeshManager()
    @Environment(\.scenePhase) private var phase
    @State private var stage = 0

    var body: some View {
        ZStack {
            Color.bg.ignoresSafeArea()
            switch stage {
            case 0: SplashView { stage = mesh.onboarded ? 2 : 1 }
            case 1: OnboardingView(mesh: mesh) { mesh.onboarded = true; stage = 2 }
            default: HomeView(mesh: mesh)
            }
        }
        .onChange(of: stage) { s in if s == 2 { mesh.start() } }
        .onChange(of: phase) { p in mesh.appActive = (p == .active) }
    }
}

// MARK: - Splash
struct SplashView: View {
    var onDone: () -> Void
    @State private var appear = false
    @State private var pulse = false

    var body: some View {
        ZStack {
            LinearGradient(colors: [Color(hex: 0x0B1220), Color(hex: 0x16254A)],
                           startPoint: .top, endPoint: .bottom).ignoresSafeArea()
            ForEach(0..<2) { i in
                Circle()
                    .stroke(Color(hex: 0x38BDF8).opacity(pulse ? 0 : 0.4), lineWidth: 2)
                    .frame(width: 260, height: 260)
                    .scaleEffect(pulse ? 1.3 : 0.4)
                    .animation(.easeOut(duration: 2).repeatForever(autoreverses: false).delay(Double(i)), value: pulse)
            }
            VStack(spacing: 16) {
                MeshLogo(size: 112)
                Text("MeshLink").font(.system(size: 34, weight: .bold)).foregroundColor(.white)
                Text("Chat without internet").font(.system(size: 15)).foregroundColor(Color(hex: 0x9FB0C8))
            }
            .opacity(appear ? 1 : 0)
            .scaleEffect(appear ? 1 : 0.9)
            .animation(.easeOut(duration: 0.7), value: appear)
        }
        .onAppear {
            appear = true
            pulse = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.8) { onDone() }
        }
    }
}

// MARK: - Onboarding
struct FeatureRow: View {
    let emoji: String, title: String, text: String
    var body: some View {
        HStack(spacing: 14) {
            Text(emoji).font(.system(size: 20))
                .frame(width: 44, height: 44)
                .background(Color.card)
                .clipShape(RoundedRectangle(cornerRadius: 14))
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 15, weight: .semibold)).foregroundColor(.textMain)
                Text(text).font(.system(size: 13)).foregroundColor(.textSub)
            }
            Spacer()
        }
        .padding(.vertical, 6)
    }
}

struct OnboardingView: View {
    @ObservedObject var mesh: MeshManager
    var onDone: () -> Void
    @State private var name = ""

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                Spacer().frame(height: 20)
                MeshLogo(size: 88)
                Text("Welcome to MeshLink").font(.system(size: 26, weight: .bold)).foregroundColor(.textMain)
                Text("Message people nearby, even when there is no signal.")
                    .font(.system(size: 14)).foregroundColor(.textSub).multilineTextAlignment(.center)
                Spacer().frame(height: 10)
                FeatureRow(emoji: "📡", title: "Works without internet", text: "Bluetooth only. No SIM, no Wi‑Fi.")
                FeatureRow(emoji: "🔁", title: "Messages hop between phones", text: "Every phone relays for the others.")
                FeatureRow(emoji: "🔒", title: "Private chats are encrypted", text: "End-to-end. Relays can't read them.")
                FeatureRow(emoji: "📦", title: "Store and forward", text: "Messages wait until someone is in range.")
                TextField("Choose your name", text: $name)
                    .padding(14)
                    .background(Color.card)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                    .onChange(of: name) { n in if n.count > 16 { name = String(n.prefix(16)) } }
                Button {
                    mesh.setNickname(name)
                    onDone()
                } label: {
                    Text("Get started").font(.system(size: 16, weight: .semibold)).foregroundColor(.white)
                        .frame(maxWidth: .infinity).frame(height: 54)
                        .background(name.trimmingCharacters(in: .whitespaces).isEmpty ? AnyShapeStyle(Color.gray.opacity(0.4)) : AnyShapeStyle(brandGradient))
                        .clipShape(RoundedRectangle(cornerRadius: 16))
                }
                .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .padding(24)
        }
        .onAppear { name = mesh.nickname }
    }
}

// MARK: - Home
struct ConvoRow<Avatar: View>: View {
    let title: String
    let subtitle: String
    let unread: Int
    let avatar: Avatar

    var body: some View {
        HStack(spacing: 14) {
            avatar
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.system(size: 16, weight: .semibold)).foregroundColor(.textMain).lineLimit(1)
                Text(subtitle).font(.system(size: 13)).foregroundColor(.textSub).lineLimit(1)
            }
            Spacer()
            if unread > 0 {
                Text("\(unread)").font(.system(size: 12, weight: .bold)).foregroundColor(.white)
                    .padding(.horizontal, 9).padding(.vertical, 3)
                    .background(brandGradient).clipShape(Capsule())
            }
        }
        .padding(14)
        .background(Color.card)
        .clipShape(RoundedRectangle(cornerRadius: 18))
    }
}

struct HomeView: View {
    @ObservedObject var mesh: MeshManager
    @State private var showSettings = false
    @State private var now = Date().timeIntervalSince1970 * 1000
    private let ticker = Timer.publish(every: 10, on: .main, in: .common).autoconnect()

    private func preview(_ convo: String) -> String? {
        mesh.messages.last(where: { $0.convo == convo }).map { ($0.mine ? "You: " : "") + $0.text }
    }

    private func ago(_ ms: Double) -> String {
        let m = Int((now - ms) / 60_000)
        if m < 1 { return "just now" }
        if m < 60 { return "\(m) min ago" }
        if m < 1440 { return "\(m / 60) h ago" }
        return "\(m / 1440) d ago"
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                HStack(spacing: 12) {
                    MeshLogo(size: 40)
                    VStack(alignment: .leading, spacing: 1) {
                        Text("MeshLink").font(.system(size: 22, weight: .bold)).foregroundColor(.textMain)
                        Text("Hi, \(mesh.nickname)").font(.system(size: 13)).foregroundColor(.textSub)
                    }
                    Spacer()
                    HStack(spacing: 6) {
                        Circle().fill(mesh.linkCount > 0 ? Color(hex: 0x22C55E) : Color(hex: 0xF59E0B))
                            .frame(width: 8, height: 8)
                        Text(mesh.linkCount > 0 ? "\(mesh.linkCount) linked" : "Searching…")
                            .font(.system(size: 12)).foregroundColor(.textSub)
                    }
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .background(Color.card).clipShape(Capsule())
                    Button { showSettings = true } label: {
                        Image(systemName: "gearshape").font(.system(size: 18)).foregroundColor(.textSub)
                    }
                }
                .padding(.horizontal, 20).padding(.vertical, 14)

                ScrollView {
                    LazyVStack(spacing: 8) {
                        NavigationLink(value: publicConvo) {
                            ConvoRow(title: "Public channel",
                                     subtitle: preview(publicConvo) ?? "Everyone nearby · not encrypted",
                                     unread: mesh.unread[publicConvo] ?? 0,
                                     avatar: HashAvatar())
                        }.buttonStyle(.plain)

                        Text("PEOPLE").font(.system(size: 12, weight: .semibold)).foregroundColor(.textSub)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.leading, 6).padding(.top, 12)

                        if mesh.peers.isEmpty {
                            Text("No one found yet. Ask a friend to open MeshLink nearby.")
                                .font(.system(size: 14)).foregroundColor(.textSub)
                                .frame(maxWidth: .infinity, alignment: .leading).padding(6)
                        }
                        ForEach(mesh.peers) { p in
                            let online = now - p.lastSeen < 90_000
                            NavigationLink(value: p.id) {
                                ConvoRow(title: p.nick,
                                         subtitle: preview(p.id) ?? (online ? "Nearby" : "Seen " + ago(p.lastSeen)),
                                         unread: mesh.unread[p.id] ?? 0,
                                         avatar: AvatarView(name: p.nick, seed: p.id, online: online))
                            }.buttonStyle(.plain)
                        }
                    }
                    .padding(16)
                }
            }
            .background(Color.bg.ignoresSafeArea())
            .navigationDestination(for: String.self) { ChatView(mesh: mesh, convo: $0) }
            .toolbar(.hidden, for: .navigationBar)
        }
        .sheet(isPresented: $showSettings) { SettingsView(mesh: mesh) }
        .onReceive(ticker) { _ in now = Date().timeIntervalSince1970 * 1000 }
    }
}

// MARK: - Chat
struct ChatView: View {
    @ObservedObject var mesh: MeshManager
    let convo: String
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""

    private var isPublic: Bool { convo == publicConvo }
    private var peer: Peer? { mesh.peers.first(where: { $0.id == convo }) }
    private var msgs: [ChatMsg] { mesh.messages.filter { $0.convo == convo } }
    private var title: String {
        if isPublic { return "Public channel" }
        return peer?.nick ?? msgs.last(where: { !$0.mine })?.nick ?? String(convo.prefix(6))
    }
    private var enabled: Bool { isPublic || peer != nil }
    private var canSend: Bool { !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && enabled }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Button { dismiss() } label: {
                    Image(systemName: "chevron.left").font(.system(size: 18, weight: .semibold)).foregroundColor(.accent)
                }
                if isPublic { HashAvatar(size: 40) } else { AvatarView(name: title, seed: convo, size: 40) }
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).font(.system(size: 17, weight: .semibold)).foregroundColor(.textMain)
                    HStack(spacing: 4) {
                        if !isPublic { Image(systemName: "lock.fill").font(.system(size: 10)) }
                        Text(isPublic ? "Visible to everyone nearby · not encrypted" : "End-to-end encrypted")
                            .font(.system(size: 12))
                    }.foregroundColor(.textSub)
                }
                Spacer()
            }
            .padding(.horizontal, 16).padding(.vertical, 10)
            .background(Color.card)

            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 6) {
                        ForEach(msgs) { m in
                            BubbleView(m: m, isPublic: isPublic).id(m.id)
                        }
                    }
                    .padding(16)
                }
                .onChange(of: msgs.count) { _ in
                    if let last = msgs.last { withAnimation { proxy.scrollTo(last.id, anchor: .bottom) } }
                }
                .onAppear {
                    if let last = msgs.last { proxy.scrollTo(last.id, anchor: .bottom) }
                }
            }
        }
        .background(Color.bg.ignoresSafeArea())
        .safeAreaInset(edge: .bottom) { inputBar }
        .toolbar(.hidden, for: .navigationBar)
        .onAppear { mesh.currentConvo = convo; mesh.markRead(convo) }
        .onDisappear { if mesh.currentConvo == convo { mesh.currentConvo = nil } }
        .onChange(of: msgs.count) { _ in mesh.markRead(convo) }
    }

    private var inputBar: some View {
        HStack(alignment: .bottom, spacing: 8) {
            TextField(enabled ? "Message" : "Waiting to discover this person…", text: $text, axis: .vertical)
                .lineLimit(1...4)
                .disabled(!enabled)
                .padding(.horizontal, 16).padding(.vertical, 12)
                .background(Color.card)
                .clipShape(RoundedRectangle(cornerRadius: 22))
                .onChange(of: text) { new in
                    if new.utf8.count > 100 {
                        var t = new
                        while t.utf8.count > 100 { t.removeLast() }
                        text = t
                    }
                }
            Button {
                if isPublic { mesh.sendPublic(text) } else { mesh.sendPrivate(to: convo, text) }
                text = ""
            } label: {
                Image(systemName: "arrow.up").font(.system(size: 18, weight: .bold)).foregroundColor(.white)
                    .frame(width: 46, height: 46)
                    .background(canSend ? AnyShapeStyle(brandGradient) : AnyShapeStyle(Color.gray.opacity(0.4)))
                    .clipShape(Circle())
            }
            .disabled(!canSend)
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
        .background(Color.bg)
    }
}

struct BubbleView: View {
    let m: ChatMsg
    let isPublic: Bool

    private var timeText: String {
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        return f.string(from: Date(timeIntervalSince1970: m.time / 1000))
    }

    var body: some View {
        HStack {
            if m.mine { Spacer(minLength: 40) }
            VStack(alignment: .leading, spacing: 3) {
                if !m.mine && isPublic {
                    Text(m.nick).font(.system(size: 12, weight: .semibold)).foregroundColor(.accent)
                }
                Text(m.text).font(.system(size: 16)).foregroundColor(m.mine ? .white : .textMain)
                HStack(spacing: 4) {
                    Text(timeText)
                    if m.mine && !isPublic { Text(m.delivered ? "✓✓" : "✓") }
                }
                .font(.system(size: 11))
                .foregroundColor(m.mine ? Color.white.opacity(0.75) : .textSub)
                .frame(maxWidth: .infinity, alignment: .trailing)
            }
            .padding(.horizontal, 14).padding(.vertical, 9)
            .background(m.mine ? AnyShapeStyle(brandGradient) : AnyShapeStyle(Color.card))
            .clipShape(RoundedRectangle(cornerRadius: 18))
            .fixedSize(horizontal: false, vertical: true)
            if !m.mine { Spacer(minLength: 40) }
        }
    }
}

// MARK: - Settings
struct SettingsView: View {
    @ObservedObject var mesh: MeshManager
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""

    private var fingerprint: String {
        let chars = Array(mesh.myIdHex)
        var parts: [String] = []
        var i = 0
        while i < chars.count {
            parts.append(String(chars[i..<min(i + 4, chars.count)]))
            i += 4
        }
        return parts.joined(separator: " ")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Settings").font(.system(size: 22, weight: .bold)).foregroundColor(.textMain)
            TextField("Display name", text: $name)
                .padding(14).background(Color.card).clipShape(RoundedRectangle(cornerRadius: 14))
                .onChange(of: name) { n in if n.count > 16 { name = String(n.prefix(16)) } }
            Text("Your ID: " + fingerprint)
                .font(.system(size: 12)).foregroundColor(.textSub)
            Text("Status: \(mesh.status)").font(.system(size: 12)).foregroundColor(.textSub)
            Text("Tip: keep Bluetooth on. iOS limits Bluetooth in the background, so open the app regularly to keep relaying.")
                .font(.system(size: 12)).foregroundColor(.textSub)
            Spacer()
            Button {
                mesh.setNickname(name)
                dismiss()
            } label: {
                Text("Save").font(.system(size: 16, weight: .semibold)).foregroundColor(.white)
                    .frame(maxWidth: .infinity).frame(height: 50)
                    .background(brandGradient).clipShape(RoundedRectangle(cornerRadius: 14))
            }
        }
        .padding(24)
        .background(Color.bg.ignoresSafeArea())
        .presentationDetents([.medium])
        .onAppear { name = mesh.nickname }
    }
}
