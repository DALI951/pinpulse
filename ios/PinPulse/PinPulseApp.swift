import SwiftUI

@main
struct PinPulseApp: App {
    @StateObject private var radar = RealtimeRadarService.shared

    var body: some Scene {
        WindowGroup {
            MainRadarView()
                .preferredColorScheme(.dark)
        }
    }
}
