import SwiftUI
import FirebaseCore
import shared

@main
struct NequiVenApp: App {
    
    init() {
        // Inicializar Firebase
        FirebaseApp.configure()
        
        // Inicializar módulo compartido KMM
        // Aquí puedes inicializar cualquier configuración compartida
    }
    
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
