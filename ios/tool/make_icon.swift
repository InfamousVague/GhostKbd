// Draws the iOS app icon from Android's launcher icon (android/app/src/main/res/drawable/ic_launcher_foreground.xml
// on its #2F6FEB ground): the same keycap and prompt, the adaptive icon's visible middle (18...90 of its 108 grid)
// filling the square. Run: swift ios/tool/make_icon.swift <out.png> [size]
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

let out = CommandLine.arguments[1]
let size = CommandLine.arguments.count > 2 ? Int(CommandLine.arguments[2])! : 1024
let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
let s = CGFloat(size) / 72
ctx.translateBy(x: 0, y: CGFloat(size)); ctx.scaleBy(x: s, y: -s); ctx.translateBy(x: -18, y: -18)
let blue = CGColor(srgbRed: 0x2F / 255, green: 0x6F / 255, blue: 0xEB / 255, alpha: 1)
ctx.setFillColor(blue); ctx.fill(CGRect(x: 18, y: 18, width: 72, height: 72))
ctx.setFillColor(CGColor(srgbRed: 1, green: 1, blue: 1, alpha: 1))
ctx.addPath(CGPath(roundedRect: CGRect(x: 28, y: 36, width: 52, height: 36), cornerWidth: 6, cornerHeight: 6, transform: nil)); ctx.fillPath()
ctx.setStrokeColor(blue); ctx.setLineWidth(4); ctx.setLineCap(.round); ctx.setLineJoin(.round)
ctx.move(to: CGPoint(x: 42, y: 47)); ctx.addLine(to: CGPoint(x: 49, y: 54)); ctx.addLine(to: CGPoint(x: 42, y: 61))
ctx.move(to: CGPoint(x: 54, y: 62)); ctx.addLine(to: CGPoint(x: 66, y: 62)); ctx.strokePath()
let dest = CGImageDestinationCreateWithURL(URL(fileURLWithPath: out) as CFURL, UTType.png.identifier as CFString, 1, nil)!
CGImageDestinationAddImage(dest, ctx.makeImage()!, nil)
CGImageDestinationFinalize(dest)
