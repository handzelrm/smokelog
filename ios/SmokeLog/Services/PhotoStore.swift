import Foundation
#if canImport(UIKit)
import UIKit
#endif

/// Cook photos as JPEGs in Application Support/photos, resized so pictures don't fill the phone.
final class PhotoStore {
    static let shared = PhotoStore()
    static let maxEdge: CGFloat = 2048

    let dir: URL

    init(directory: URL? = nil) {
        dir = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("photos", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    func url(_ fileName: String) -> URL { dir.appendingPathComponent(fileName) }

    /// Saves an image (camera or library), downscaled and re-encoded as JPEG. Returns the file name.
    func save(imageData: Data) throws -> String {
        #if canImport(UIKit)
        guard let image = UIImage(data: imageData) else { throw AIError(message: "Couldn't read that image") }
        return try save(image: image)
        #else
        return try save(jpeg: imageData)
        #endif
    }

    #if canImport(UIKit)
    func save(image: UIImage) throws -> String {
        let longest = max(image.size.width, image.size.height)
        let scale = min(1, Self.maxEdge / max(longest, 1))
        let size = CGSize(width: (image.size.width * scale).rounded(), height: (image.size.height * scale).rounded())
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        // Drawing also bakes in the camera's orientation.
        let resized = UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        guard let jpeg = resized.jpegData(compressionQuality: 0.85) else { throw AIError(message: "Couldn't save that image") }
        return try save(jpeg: jpeg)
    }
    #endif

    /// Stores JPEG bytes as-is (e.g. received in a shared cook file).
    func save(jpeg: Data) throws -> String {
        let name = "\(UUID().uuidString).jpg"
        try jpeg.write(to: url(name), options: .atomic)
        return name
    }

    func delete(_ fileName: String) { try? FileManager.default.removeItem(at: url(fileName)) }
}
