import AppKit

// Rasterize the existing Android vector icon for store listings.
let destination = URL(fileURLWithPath: CommandLine.arguments[1])
let size = 512
let context = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8,
                        bytesPerRow: size * 4, space: CGColorSpaceCreateDeviceRGB(),
                        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
context.setFillColor(CGColor(red: 48 / 255, green: 77 / 255, blue: 59 / 255, alpha: 1))
context.fill(CGRect(x: 0, y: 0, width: size, height: size))
context.translateBy(x: 0, y: CGFloat(size))
context.scaleBy(x: CGFloat(size) / 108, y: -CGFloat(size) / 108)
let book = CGMutablePath()
book.move(to: CGPoint(x: 29, y: 32))
book.addLine(to: CGPoint(x: 48, y: 32))
book.addQuadCurve(to: CGPoint(x: 54, y: 38), control: CGPoint(x: 54, y: 32))
book.addQuadCurve(to: CGPoint(x: 60, y: 32), control: CGPoint(x: 54, y: 32))
book.addLine(to: CGPoint(x: 79, y: 32))
book.addLine(to: CGPoint(x: 79, y: 74))
book.addLine(to: CGPoint(x: 60, y: 74))
book.addQuadCurve(to: CGPoint(x: 54, y: 80), control: CGPoint(x: 54, y: 74))
book.addQuadCurve(to: CGPoint(x: 48, y: 74), control: CGPoint(x: 54, y: 74))
book.addLine(to: CGPoint(x: 29, y: 74))
book.closeSubpath()
context.addPath(book)
context.setFillColor(CGColor(red: 246 / 255, green: 245 / 255, blue: 239 / 255, alpha: 1))
context.fillPath()
context.move(to: CGPoint(x: 54, y: 39))
context.addLine(to: CGPoint(x: 54, y: 72))
context.setStrokeColor(CGColor(red: 48 / 255, green: 77 / 255, blue: 59 / 255, alpha: 1))
context.setLineWidth(2)
context.strokePath()
let image = NSBitmapImageRep(cgImage: context.makeImage()!)
try image.representation(using: .png, properties: [:])!.write(to: destination)
