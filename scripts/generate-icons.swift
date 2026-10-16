import AppKit
let root = CommandLine.arguments[1]
let sizes = [16, 32, 64, 128, 256, 512, 1024]
for size in sizes {
    let context = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: size * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    let scale = CGFloat(size) / 512
    context.scaleBy(x: scale, y: scale)
    context.setFillColor(CGColor(red: 0.19, green: 0.30, blue: 0.23, alpha: 1))
    context.fill(CGRect(x: 0, y: 0, width: 512, height: 512))
    context.setStrokeColor(CGColor(red: 0.966, green: 0.960, blue: 0.937, alpha: 1))
    context.setLineWidth(26)
    context.setLineCap(.round)
    for (x, low, high) in [(163.0, 147.0, 365.0), (256.0, 112.0, 400.0), (349.0, 176.0, 336.0)] {
        context.move(to: CGPoint(x: x, y: low)); context.addLine(to: CGPoint(x: x, y: high)); context.strokePath()
    }
    let bitmap = NSBitmapImageRep(cgImage: context.makeImage()!)
    try bitmap.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: root).appendingPathComponent("icon-\(size).png"))
}
