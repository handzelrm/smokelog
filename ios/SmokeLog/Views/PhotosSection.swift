import PhotosUI
import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// Take or add pictures of the cook, shown as a strip of thumbnails; tap one to view, caption or delete it.
struct PhotosSection: View {
    let cook: Cook
    @Environment(CookStore.self) private var store
    @State private var picked: [PhotosPickerItem] = []
    @State private var showCamera = false
    @State private var viewing: CookPhoto?
    @State private var error: String?

    var body: some View {
        Section("Photos") {
            let photos = cook.photoList
            if photos.isEmpty {
                Text("Bark, smoke ring, the slice… snap them as you go.").font(.subheadline).foregroundStyle(.secondary)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(photos) { p in
                            Button { viewing = p } label: {
                                VStack(alignment: .leading, spacing: 4) {
                                    StoredPhoto(fileName: p.fileName)
                                        .frame(width: 104, height: 104)
                                        .clipShape(RoundedRectangle(cornerRadius: 10))
                                    Text(p.caption.isEmpty ? elapsed(p.time) : p.caption)
                                        .font(.caption2).foregroundStyle(.secondary).lineLimit(1).frame(width: 104, alignment: .leading)
                                }
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(p.caption.isEmpty ? "Cook photo" : p.caption)
                        }
                    }
                }
            }
            HStack {
                if Platform.cameraAvailable {
                    Button { showCamera = true } label: { Label("Take photo", systemImage: "camera") }
                        .buttonStyle(.bordered)
                }
                PhotosPicker(selection: $picked, maxSelectionCount: 10, matching: .images) {
                    Label("Add photos", systemImage: "photo.badge.plus")
                }
                .buttonStyle(.bordered)
            }
            if let error { Text(error).font(.footnote).foregroundStyle(.red) }
        }
        .onChange(of: picked) { _, items in
            guard !items.isEmpty else { return }
            Task { await addPicked(items) }
        }
        #if os(iOS)
        .fullScreenCover(isPresented: $showCamera) {
            CameraPicker { image in
                if let image { add { try PhotoStore.shared.save(image: image) } }
                showCamera = false
            }
            .ignoresSafeArea()
        }
        #endif
        .sheet(item: $viewing) { p in
            PhotoViewer(photo: p, startTime: cook.startTime,
                        onCaption: { caption in update(p.id) { $0.caption = caption } },
                        onDelete: {
                            PhotoStore.shared.delete(p.fileName)
                            store.update(cook.id) { $0.photos = ($0.photos ?? []).filter { $0.id != p.id } }
                            viewing = nil
                        })
        }
    }

    /// "5h 10m in" for photos taken during the cook; otherwise the date (or time, if today).
    private func elapsed(_ t: Date) -> String {
        if let s = cook.startTime, t >= s, t.timeIntervalSince(s) < 24 * 3600 {
            return "\(Fmt.duration(Int(t.timeIntervalSince(s) / 60))) in"
        }
        return Calendar.current.isDateInToday(t) ? Fmt.time(t) : Fmt.date(t)
    }

    private func add(_ save: () throws -> String) {
        do {
            let name = try save()
            store.update(cook.id) { $0.photos = ($0.photos ?? []) + [CookPhoto(time: Date(), fileName: name)] }
            error = nil
        } catch {
            self.error = "Couldn't add that photo: \(error.localizedDescription)"
        }
    }

    @MainActor private func addPicked(_ items: [PhotosPickerItem]) async {
        for item in items {
            if let data = try? await item.loadTransferable(type: Data.self) {
                add { try PhotoStore.shared.save(imageData: data) }
            } else {
                error = "Couldn't load one of the selected photos."
            }
        }
        picked = []
    }

    private func update(_ id: UUID, _ change: (inout CookPhoto) -> Void) {
        store.update(cook.id) { c in
            guard var list = c.photos, let i = list.firstIndex(where: { $0.id == id }) else { return }
            change(&list[i])
            c.photos = list
        }
    }
}

/// Loads a stored photo off the main thread.
struct StoredPhoto: View {
    let fileName: String
    var fit = false
    @State private var image: Image?

    var body: some View {
        ZStack {
            Color.secondary.opacity(0.15)
            if let image {
                image.resizable().aspectRatio(contentMode: fit ? .fit : .fill)
            }
        }
        .task(id: fileName) {
            let url = PhotoStore.shared.url(fileName)
            image = await Task.detached { Platform.image(at: url) }.value
        }
    }
}

struct PhotoViewer: View {
    let photo: CookPhoto
    let startTime: Date?
    let onCaption: (String) -> Void
    let onDelete: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var caption = ""
    @State private var confirmDelete = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                StoredPhoto(fileName: photo.fileName, fit: true).frame(maxWidth: .infinity, maxHeight: .infinity)
                Text(Fmt.dateTime(photo.time) + (startTime.flatMap { s in
                    let t = photo.time.timeIntervalSince(s)
                    return t >= 0 && t < 24 * 3600 ? " · \(Fmt.duration(Int(t / 60))) in" : nil
                } ?? ""))
                    .font(.caption).foregroundStyle(.secondary)
                TextField("Add a caption (e.g. bark after 6 h)", text: $caption, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
            }
            .padding()
            .navigationTitle("Photo")
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Delete", role: .destructive) { confirmDelete = true }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { onCaption(caption.trimmingCharacters(in: .whitespacesAndNewlines)); dismiss() }
                }
            }
            .confirmationDialog("Delete this photo?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Delete photo", role: .destructive, action: onDelete)
            }
            .onAppear { caption = photo.caption }
        }
    }
}

#if os(iOS)
/// The system camera (UIImagePickerController); hands back the photo, or nil if cancelled.
struct CameraPicker: UIViewControllerRepresentable {
    let onFinish: (UIImage?) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onFinish: onFinish) }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ vc: UIImagePickerController, context: Context) {}

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onFinish: (UIImage?) -> Void
        init(onFinish: @escaping (UIImage?) -> Void) { self.onFinish = onFinish }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            onFinish(info[.originalImage] as? UIImage)
        }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { onFinish(nil) }
    }
}
#endif
