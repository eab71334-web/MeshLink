import SwiftUI

struct ContentView: View {
    @StateObject private var mesh = MeshManager()
    @State private var input = ""

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("MeshLink").font(.title2).bold()
                Spacer()
                Text("Peers: \(mesh.peerCount)")
            }
            .padding([.horizontal, .top])
            Text(mesh.status).font(.caption).foregroundColor(.secondary)

            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(mesh.messages) { m in
                            HStack {
                                if m.mine { Spacer() }
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(m.nick).font(.caption2).foregroundColor(.secondary)
                                    Text(m.text)
                                }
                                .padding(8)
                                .background(m.mine ? Color.blue.opacity(0.2) : Color.gray.opacity(0.2))
                                .cornerRadius(10)
                                if !m.mine { Spacer() }
                            }
                            .id(m.id)
                        }
                    }
                    .padding()
                }
                .onChange(of: mesh.messages.count) { _ in
                    if let last = mesh.messages.last { proxy.scrollTo(last.id) }
                }
            }

            HStack {
                TextField("Message", text: $input).textFieldStyle(.roundedBorder)
                Button("Send") { mesh.send(input); input = "" }
                    .disabled(input.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .padding()
        }
    }
}
