import SwiftUI
import MapKit

struct MainRadarView: View {
    @ObservedObject var radar = RealtimeRadarService.shared
    @State private var region = MKCoordinateRegion(
        center: CLLocationCoordinate2D(latitude: 36.8065, longitude: 10.1815),
        span: MKCoordinateSpan(latitudeDelta: 0.05, longitudeDelta: 0.05)
    )
    @State private var showPlanSheet = false
    @State private var showSosDialog = false

    var body: some View {
        ZStack {
            Map(coordinateRegion: $region, annotationItems: Array(radar.friends.values) + [radar.currentUser]) { user in
                MapAnnotation(coordinate: user.coordinate) {
                    VStack(spacing: 2) {
                        if !user.isGpsEnabled {
                            Text("📍 GPS OFF")
                                .font(.system(size: 9, weight: .black))
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(Color.orange)
                                .foregroundColor(.black)
                                .cornerRadius(6)
                        }
                        ZStack {
                            Circle()
                                .fill(user.isGpsEnabled ? Color.green : Color.orange)
                                .frame(width: 38, height: 38)
                            Text(String(user.displayName.prefix(1)).uppercased())
                                .font(.system(size: 16, weight: .bold))
                                .foregroundColor(.black)
                        }
                        Text("\(user.displayName) • \(user.batteryPercent)%")
                            .font(.system(size: 10, weight: .bold))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color.black.opacity(0.8))
                            .foregroundColor(.white)
                            .cornerRadius(6)
                    }
                }
            }
            .ignoresSafeArea()

            VStack(spacing: 8) {
                // Top Subscription Status Banner
                Button(action: { showPlanSheet = true }) {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("PACK: \(radar.currentUser.subscriptionPlan) • \(radar.currentUser.remainingTimeFormatted)")
                                .font(.system(size: 12, weight: .heavy))
                                .foregroundColor(.green)
                            Text("Quotas: \(radar.currentUser.friendsCount)/\(radar.currentUser.maxFriends) Friends • \(radar.currentUser.groupsCount)/\(radar.currentUser.maxGroups) Groups • Rate: \(radar.currentUser.rateLimitSec)s")
                                .font(.system(size: 10, weight: .medium))
                                .foregroundColor(.white.opacity(0.8))
                        }
                        Spacer()
                        Text("My Plan ›")
                            .font(.system(size: 11, weight: .bold))
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(Color.green.opacity(0.2))
                            .foregroundColor(.green)
                            .cornerRadius(8)
                    }
                    .padding(12)
                    .background(Color(red: 0.06, green: 0.09, blue: 0.16).opacity(0.95))
                    .cornerRadius(14)
                }
                .padding(.horizontal, 12)
                .padding(.top, 8)

                // GPS Off Warning Banner
                if !radar.isSystemGpsEnabled {
                    HStack {
                        Text("📍 Your iPhone GPS is OFF. Turn on Location Services so friends can see your live position.")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(.black)
                        Spacer()
                        Button("Turn On") {
                            if let url = URL(string: UIApplication.openSettingsURLString) {
                                UIApplication.shared.open(url)
                            }
                        }
                        .font(.system(size: 11, weight: .black))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(Color.black)
                        .foregroundColor(.orange)
                        .cornerRadius(8)
                    }
                    .padding(10)
                    .background(Color.orange)
                    .cornerRadius(12)
                    .padding(.horizontal, 12)
                }

                Spacer()

                // Floating SOS Button
                HStack {
                    Spacer()
                    Button(action: { showSosDialog = true }) {
                        VStack(spacing: 1) {
                            Text("🆘")
                                .font(.system(size: 16))
                            Text("SOS")
                                .font(.system(size: 11, weight: .black))
                                .foregroundColor(.white)
                        }
                        .frame(width: 58, height: 58)
                        .background(Color.red)
                        .clipShape(Circle())
                        .shadow(color: .red.opacity(0.6), radius: 10)
                    }
                    .padding(.trailing, 18)
                    .padding(.bottom, 24)
                }
            }
        }
        .alert("🆘 Send Emergency SOS Broadcast?", isPresented: $showSosDialog) {
            Button("🚨 SEND SOS NOW", role: .destructive) {
                radar.triggerSosEmergencyBroadcast()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This immediately alerts all your Friends, Circle Groups, and Fleet Command with your exact GPS coordinates, battery level, and Google Maps link.")
        }
        .sheet(isPresented: $showPlanSheet) {
            VStack(spacing: 16) {
                Text("💼 My Subscription & Quotas")
                    .font(.title3.bold())
                Text("Active Plan: \(radar.currentUser.subscriptionPlan)")
                    .font(.headline)
                Text("Remaining Time: \(radar.currentUser.remainingTimeFormatted)")
                    .foregroundColor(.secondary)
                Divider()
                VStack(alignment: .leading, spacing: 10) {
                    Text("🎁 DEMO Pack: 1 Friend • 0 Groups • 7 Days Free")
                    Text("👨‍👩‍👧‍👦 FAMILY Pack: 10 Friends • 2 Groups • 30 Days")
                    Text("🏢 BUSINESS Pack: 50 Friends • 10 Groups • 30 Days")
                    Text("⚡ CUSTOM Enterprise: 100+ Friends & Custom Groups")
                }
                .font(.subheadline)
                Button("Close") { showPlanSheet = false }
                    .buttonStyle(.borderedProminent)
            }
            .padding(24)
        }
    }
}
