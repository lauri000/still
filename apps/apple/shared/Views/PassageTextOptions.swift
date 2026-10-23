import SwiftUI

struct PassageTextOptions: View {
    @Binding var size: Int
    let dismiss: () -> Void
    @ScaledMetric private var baseSize: CGFloat = 22
    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            Text("Text size").font(.system(size: 22, weight: .semibold))
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    Text("A quiet moment with a good book.")
                        .font(.custom("Georgia", fixedSize: baseSize * CGFloat(size) / 22))
                        .lineSpacing(baseSize * CGFloat(size) / 22 * 0.3)
                        .frame(maxWidth: .infinity, alignment: .leading).padding(20)
                        .background(Palette.surface, in: RoundedRectangle(cornerRadius: 12))
                    HStack {
                        Button { size = max(16, size - 2) } label: { Text("A−").font(.system(size: 18)).frame(minWidth: 42) }
                            .buttonStyle(SecondaryButton()).disabled(size <= 16)
                            .accessibilityLabel("Smaller text").accessibilityIdentifier("smallerPassageText")
                        Spacer()
                        Text("\(size)").font(.system(size: 18)).monospacedDigit().accessibilityIdentifier("passageTextSizeValue")
                        Spacer()
                        Button { size = min(32, size + 2) } label: { Text("A+").font(.system(size: 24)).frame(minWidth: 42) }
                            .buttonStyle(SecondaryButton()).disabled(size >= 32)
                            .accessibilityLabel("Larger text").accessibilityIdentifier("largerPassageText")
                    }
                    Text("Saved for scrolling view.").font(.callout).foregroundStyle(Palette.muted)
                }
            }
            HStack {
                Button("Reset") { size = 22 }.buttonStyle(SecondaryButton()).disabled(size == 22)
                Spacer()
                Button("Done", action: dismiss).buttonStyle(PrimaryButton())
            }
        }.padding(28).frame(idealWidth: 420, idealHeight: 460).background(Palette.paper).foregroundStyle(Palette.ink)
        #if os(iOS)
        .presentationDetents([.height(460), .large])
        #endif
    }
}
