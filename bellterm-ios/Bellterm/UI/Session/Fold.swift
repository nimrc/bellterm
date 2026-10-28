import SwiftUI

/// How a foldable is held, as far as layout cares.
enum Posture: Equatable {
    case flat
    /// Partly folded, like a laptop: the upper half is a screen, the lower a keyboard deck.
    case tabletop
}

extension View {
    /// Reports the hinge posture on iPhone Duo; other devices stay `.flat`.
    @ViewBuilder
    func onPostureChange(_ action: @escaping (Posture) -> Void) -> some View {
        if #available(iOS 27.1, *) {
            onHingeChange { _, context in
                action(context.hinge?.status == .partiallyOpen ? .tabletop : .flat)
            }
        } else {
            self
        }
    }

    /// Reports the frame of a horizontal fold crossing this view, in its own
    /// coordinates, or nil when there is none.
    func onHorizontalFoldChange(_ action: @escaping (CGRect?) -> Void) -> some View {
        onGeometryChange(for: CGRect?.self) { proxy in
            guard #available(iOS 27.1, *) else { return nil }
            return proxy.reservedRegions(kind: .division)
                .first { $0.isActive && $0.frame.width > $0.frame.height }?
                .frame
        } action: { action($0) }
    }
}
