import SwiftUI

/// The fold of iPhone Duo, as the division reserved region it becomes while
/// the device is partly folded. Layout reads the region, never the hinge: a
/// region wider than tall is the tabletop pose, taller than wide the book pose.
enum Fold {
    /// The active fold crossing `proxy`'s view, in its coordinates, or nil
    /// when the device is flat, closed, or has no fold.
    static func active(in proxy: GeometryProxy) -> CGRect? {
        guard #available(iOS 27.1, *) else { return nil }
        return proxy.reservedRegions(kind: .division).first(where: \.isActive)?.frame
    }

    static func horizontal(in proxy: GeometryProxy) -> CGRect? {
        active(in: proxy).flatMap { $0.width > $0.height ? $0 : nil }
    }

    static func vertical(in proxy: GeometryProxy) -> CGRect? {
        active(in: proxy).flatMap { $0.height > $0.width ? $0 : nil }
    }
}
