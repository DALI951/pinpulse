import Foundation
import CoreLocation
import UIKit
import Combine

final class RealtimeRadarService: NSObject, ObservableObject, CLLocationManagerDelegate {
    static let shared = RealtimeRadarService()

    @Published var currentUser: PinPulseUser
    @Published var friends: [String: PinPulseUser] = [:]
    @Published var isSystemGpsEnabled: Bool = true
    @Published var toastMessage: String? = nil

    private let locationManager = CLLocationManager()
    private var heartbeatTimer: Timer?

    override init() {
        let savedId = UserDefaults.standard.string(forKey: "pp_user_id") ?? ("ios_" + UUID().uuidString.prefix(8))
        let savedCode = UserDefaults.standard.string(forKey: "pp_friend_code") ?? String((100000...999999).randomElement()!)
        UserDefaults.standard.set(savedId, forKey: "pp_user_id")
        UserDefaults.standard.set(savedCode, forKey: "pp_friend_code")

        self.currentUser = PinPulseUser(
            id: savedId,
            email: UserDefaults.standard.string(forKey: "pp_email") ?? "ios.user@pinpulse.app",
            displayName: UserDefaults.standard.string(forKey: "pp_name") ?? "iPhone User",
            friendCode: "PP-\(savedCode)"
        )
        super.init()

        UIDevice.current.isBatteryMonitoringEnabled = true
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        locationManager.allowsBackgroundLocationUpdates = true
        locationManager.pausesLocationUpdatesAutomatically = false
        locationManager.requestAlwaysAuthorization()
        locationManager.startUpdatingLocation()

        startTelemetryLoop()
    }

    func startTelemetryLoop() {
        heartbeatTimer?.invalidate()
        let interval = TimeInterval(max(1, currentUser.rateLimitSec))
        heartbeatTimer = Timer.scheduledTimer(withTimeInterval: interval, repeats: true) { [weak self] _ in
            self?.refreshDeviceState()
        }
    }

    func refreshDeviceState() {
        let gpsEnabled = CLLocationManager.locationServicesEnabled() &&
            (locationManager.authorizationStatus == .authorizedAlways || locationManager.authorizationStatus == .authorizedWhenInUse)
        DispatchQueue.main.async {
            self.isSystemGpsEnabled = gpsEnabled
            self.currentUser.isGpsEnabled = gpsEnabled
            let bat = Int(UIDevice.current.batteryLevel * 100)
            if bat >= 0 {
                self.currentUser.batteryPercent = bat
            }
            self.currentUser.isCharging = (UIDevice.current.batteryState == .charging || UIDevice.current.batteryState == .full)
            self.currentUser.lastUpdated = Int64(Date().timeIntervalSince1970 * 1000)
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }
        DispatchQueue.main.async {
            self.currentUser.latitude = loc.coordinate.latitude
            self.currentUser.longitude = loc.coordinate.longitude
            self.currentUser.speedKmh = max(0, Float(loc.speed * 3.6))
            self.currentUser.isGpsEnabled = true
            self.isSystemGpsEnabled = true
            self.currentUser.lastUpdated = Int64(Date().timeIntervalSince1970 * 1000)
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        refreshDeviceState()
    }

    func canAddFriend() -> Bool {
        if currentUser.accountStatus != "ACTIVE" || currentUser.isSubscriptionExpired {
            toastMessage = "⚠️ Your \(currentUser.subscriptionPlan) plan is \(currentUser.remainingTimeFormatted)."
            return false
        }
        if currentUser.friendsCount >= currentUser.maxFriends {
            toastMessage = "🔒 \(currentUser.subscriptionPlan) Pack limit reached (\(currentUser.maxFriends) friend(s) max). Upgrade to Family or Business!"
            return false
        }
        return true
    }

    func triggerSosEmergencyBroadcast() {
        let lat = currentUser.latitude
        let lng = currentUser.longitude
        toastMessage = "🆘 SOS Emergency Broadcast sent to all Friends, Groups & Fleet Command! (\(String(format: "%.5f, %.5f", lat, lng)))"
    }
}
