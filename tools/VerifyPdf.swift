import Foundation
import PDFKit
import AppKit

let base = CommandLine.arguments[1]
for count in 1...2 {
    let url = URL(fileURLWithPath: "\(base)/test-\(count).pdf")
    guard let document = PDFDocument(url: url), document.pageCount == count else {
        fatalError("Native PDF reader rejected page count")
    }
    for index in 0..<count {
        guard let page = document.page(at: index) else { fatalError("Missing PDF page") }
        let image = page.thumbnail(of: NSSize(width: 300, height: 400), for: .mediaBox)
        guard let data = image.tiffRepresentation, let bitmap = NSBitmapImageRep(data: data),
              let color = bitmap.colorAt(x: 30, y: 30)?.usingColorSpace(.deviceRGB) else {
            fatalError("PDF page could not render")
        }
        if index == 0 { precondition(color.redComponent > color.blueComponent + 0.6) }
        else { precondition(color.blueComponent > color.redComponent + 0.6) }
        try bitmap.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: "\(base)/render-\(count)-\(index).png"))
    }
    print("PASS Native PDFKit opens and renders \(count)-page PDF in correct order")
}
