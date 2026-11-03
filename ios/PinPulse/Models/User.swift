import Foundation
import CoreLocation

struct PinPulseUser: Identifiable, Codable {
    var id: String
    var email: String
    var displayName: String
    var friendCode: String
    var latitude: Double = 0.0
    var longitude: Double = 0.0
    var speedKmh: Float = 0.0
    var batteryPercent: Int = 100
    var isCharging: Bool = false
    var isSharingLocation: Bool = true
    var isGpsEnabled: Bool = true
    var lastUpdated: Int64 = Int64(Date().timeIntervalSince1970 * 1000)

    // Commercial SaaS Subscription & Quotas
    var subscriptionPlan: String = "DEMO" // DEMO, FAMILY, BUSINESS, CUSTOM
    var accountStatus: String = "ACTIVE"  // ACTIVE, DEACTIVATED, BANNED, EXPIRED, DELETED
    var subscriptionStartAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    var subscriptionExpiresAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000) + (7 * 24 * 60 * 60 * 1000)
    var maxFriends: Int = 1
    var maxGroups: Int = 0
    var rateLimitSec: Int = 5
    var monthlyRateUsd: Double = 0.0
    var friendsCount: Int = 0
    var groupsCount: Int = 0

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }

    var isSubscriptionExpired: Bool {
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        return accountStatus.uppercased() == "EXPIRED" || nowMs > subscriptionExpiresAt
    }

    var remainingTimeFormatted: String {
        if accountStatus.uppercased() == "BANNED" { return "Account Banned" }
        if accountStatus.uppercased() == "DEACTIVATED" { return "Paused (Unpaid)" }
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let diff = subscriptionExpiresAt - nowMs
        if diff <= 0 { return "Expired (0d left)" }
        let days = diff / (24 * 60 * 60 * 1000)
        let hours = (diff % (24 * 60 * 60 * 1000)) / (60 * 60 * 1000)
        if days > 0 {
            return "\(days)d \(hours)h left"
        }
        let mins = max(1, (diff % (60 * 60 * 1000)) / (60 * 1000))
        return "\(hours)h \(mins)m left"
    }
}
