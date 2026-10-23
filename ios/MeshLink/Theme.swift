import SwiftUI

extension Color {
    init(hex: UInt32) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: 1)
    }

    static func dyn(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(Color(hex: dark)) : UIColor(Color(hex: light)) })
    }

    static let bg = Color.dyn(0xF3F6FB, 0x0B1220)
    static let card = Color.dyn(0xFFFFFF, 0x121B2E)
    static let textMain = Color.dyn(0x0F172A, 0xE6EDF7)
    static let textSub = Color.dyn(0x5B6B84, 0x9FB0C8)
    static let accent = Color.dyn(0x0B7FD1, 0x38BDF8)
}

let brandGradient = LinearGradient(
    colors: [Color(hex: 0x0EA5E9), Color(hex: 0x6366F1)],
    startPoint: .topLeading, endPoint: .bottomTrailing
)

struct MeshLogo: View {
    var size: CGFloat

    var body: some View {
        Canvas { ctx, sz in
            let w = sz.width
            ctx.fill(
                Path(roundedRect: CGRect(x: 0, y: 0, width: w, height: w), cornerRadius: w * 0.24),
                with: .linearGradient(
                    Gradient(colors: [Color(hex: 0x0EA5E9), Color(hex: 0x6366F1)]),
                    startPoint: .zero, endPoint: CGPoint(x: w, y: w))
            )
            let a = CGPoint(x: w * 0.5, y: w * 0.30)
            let b = CGPoint(x: w * 0.30, y: w * 0.68)
            let c = CGPoint(x: w * 0.70, y: w * 0.68)
            var lines = Path()
            lines.move(to: a); lines.addLine(to: b); lines.addLine(to: c); lines.addLine(to: a)
            ctx.stroke(lines, with: .color(.white),
                       style: StrokeStyle(lineWidth: w * 0.045, lineCap: .round, lineJoin: .round))
            for p in [a, b, c] {
                ctx.fill(Path(ellipseIn: CGRect(x: p.x - w * 0.085, y: p.y - w * 0.085,
                                                width: w * 0.17, height: w * 0.17)),
                         with: .color(.white))
            }
        }
        .frame(width: size, height: size)
    }
}

struct AvatarView: View {
    let name: String
    let seed: String
    var size: CGFloat = 46
    var online = false

    private var color: Color {
        let palette: [UInt32] = [0x0EA5E9, 0x8B5CF6, 0x10B981, 0xF59E0B, 0xEC4899, 0x14B8A6]
        let h = seed.unicodeScalars.reduce(0) { ($0 &* 31 &+ Int($1.value)) & 0x7fffffff }
        return Color(hex: palette[h % palette.count])
    }

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            Text(String(name.first ?? "?").uppercased())
                .font(.system(size: size * 0.4, weight: .bold))
                .foregroundColor(.white)
                .frame(width: size, height: size)
                .background(color)
                .clipShape(Circle())
            if online {
                Circle().fill(Color(hex: 0x22C55E)).frame(width: 11, height: 11)
                    .overlay(Circle().stroke(Color.card, lineWidth: 2))
            }
        }
    }
}

struct HashAvatar: View {
    var size: CGFloat = 46
    var body: some View {
        Text("#")
            .font(.system(size: size * 0.48, weight: .bold))
            .foregroundColor(.white)
            .frame(width: size, height: size)
            .background(brandGradient)
            .clipShape(Circle())
    }
}
