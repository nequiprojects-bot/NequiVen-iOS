import SwiftUI
import shared

struct ContentView: View {
    @State private var isLoading = true
    @State private var isLoggedIn = false
    
    var body: some View {
        NavigationView {
            ZStack {
                // Color de fondo morado de Nequi
                Color(red: 0.13, green: 0, blue: 0.13)
                    .ignoresSafeArea()
                
                if isLoading {
                    SplashView()
                } else if isLoggedIn {
                    HomeView()
                } else {
                    LoginView(isLoggedIn: $isLoggedIn)
                }
            }
        }
        .onAppear {
            // Simular carga inicial
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                withAnimation {
                    isLoading = false
                }
            }
        }
    }
}

struct SplashView: View {
    var body: some View {
        VStack(spacing: 20) {
            // Logo o icono de la app
            Image(systemName: "creditcard.fill")
                .resizable()
                .scaledToFit()
                .frame(width: 120, height: 120)
                .foregroundColor(.white)
            
            Text("Nequi Ven V5.1")
                .font(.system(size: 32, weight: .bold))
                .foregroundColor(.white)
            
            ProgressView()
                .progressViewStyle(CircularProgressViewStyle(tint: .white))
                .scaleEffect(1.5)
        }
    }
}

struct LoginView: View {
    @Binding var isLoggedIn: Bool
    @State private var phoneNumber = ""
    @State private var password = ""
    @State private var showError = false
    @State private var errorMessage = ""
    
    var body: some View {
        ScrollView {
            VStack(spacing: 30) {
                // Header
                VStack(spacing: 10) {
                    Image(systemName: "creditcard.fill")
                        .resizable()
                        .scaledToFit()
                        .frame(width: 80, height: 80)
                        .foregroundColor(.white)
                    
                    Text("Bienvenido a Nequi Ven")
                        .font(.system(size: 28, weight: .bold))
                        .foregroundColor(.white)
                    
                    Text("Ingresa tus datos para continuar")
                        .font(.system(size: 16))
                        .foregroundColor(.white.opacity(0.8))
                }
                .padding(.top, 60)
                
                // Formulario
                VStack(spacing: 20) {
                    // Campo de teléfono
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Número de teléfono")
                            .font(.system(size: 14, weight: .medium))
                            .foregroundColor(.white)
                        
                        TextField("", text: $phoneNumber)
                            .keyboardType(.phonePad)
                            .textContentType(.telephoneNumber)
                            .padding()
                            .background(Color.white.opacity(0.1))
                            .cornerRadius(12)
                            .foregroundColor(.white)
                            .overlay(
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(Color.white.opacity(0.3), lineWidth: 1)
                            )
                    }
                    
                    // Campo de contraseña
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Contraseña")
                            .font(.system(size: 14, weight: .medium))
                            .foregroundColor(.white)
                        
                        SecureField("", text: $password)
                            .textContentType(.password)
                            .padding()
                            .background(Color.white.opacity(0.1))
                            .cornerRadius(12)
                            .foregroundColor(.white)
                            .overlay(
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(Color.white.opacity(0.3), lineWidth: 1)
                            )
                    }
                    
                    // Botón de login
                    Button(action: {
                        login()
                    }) {
                        Text("Ingresar")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundColor(Color(red: 0.13, green: 0, blue: 0.13))
                            .frame(maxWidth: .infinity)
                            .padding()
                            .background(Color.white)
                            .cornerRadius(12)
                    }
                    .padding(.top, 10)
                    
                    // Botón de registro
                    Button(action: {
                        // Acción de registro
                    }) {
                        Text("¿No tienes cuenta? Regístrate")
                            .font(.system(size: 16))
                            .foregroundColor(.white)
                    }
                }
                .padding(.horizontal, 30)
                
                Spacer()
            }
        }
        .alert("Error", isPresented: $showError) {
            Button("OK", role: .cancel) { }
        } message: {
            Text(errorMessage)
        }
    }
    
    private func login() {
        // Validación básica
        guard !phoneNumber.isEmpty else {
            errorMessage = "Por favor ingresa tu número de teléfono"
            showError = true
            return
        }
        
        guard !password.isEmpty else {
            errorMessage = "Por favor ingresa tu contraseña"
            showError = true
            return
        }
        
        // Aquí llamarías al AuthRepository del módulo shared
        // Por ahora, simulamos login exitoso
        withAnimation {
            isLoggedIn = true
        }
    }
}

struct HomeView: View {
    @State private var userName = "Nequi Ven"
    @State private var balance = "$0"
    
    var body: some View {
        ScrollView {
            VStack(spacing: 25) {
                // Header con saludo y nombre
                VStack(alignment: .leading, spacing: 8) {
                    Text("Hola,")
                        .font(.system(size: 18))
                        .foregroundColor(.white.opacity(0.8))
                    
                    Text(userName)
                        .font(.system(size: 28, weight: .bold))
                        .foregroundColor(.white)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 20)
                .padding(.top, 60)
                
                // Tarjeta de saldo
                VStack(spacing: 15) {
                    Text("Saldo disponible")
                        .font(.system(size: 16))
                        .foregroundColor(.white.opacity(0.8))
                    
                    Text(balance)
                        .font(.system(size: 40, weight: .bold))
                        .foregroundColor(.white)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 30)
                .background(
                    RoundedRectangle(cornerRadius: 20)
                        .fill(Color.white.opacity(0.1))
                )
                .padding(.horizontal, 20)
                
                // Botones de acciones principales
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 15) {
                    ActionButton(icon: "arrow.up.circle.fill", title: "Enviar", color: .green)
                    ActionButton(icon: "arrow.down.circle.fill", title: "Recibir", color: .blue)
                    ActionButton(icon: "qrcode", title: "Pagar QR", color: .orange)
                    ActionButton(icon: "clock.fill", title: "Historial", color: .purple)
                }
                .padding(.horizontal, 20)
                
                Spacer()
            }
        }
        .background(Color(red: 0.13, green: 0, blue: 0.13))
    }
}

struct ActionButton: View {
    let icon: String
    let title: String
    let color: Color
    
    var body: some View {
        Button(action: {
            // Acción del botón
        }) {
            VStack(spacing: 12) {
                Image(systemName: icon)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 40, height: 40)
                    .foregroundColor(color)
                
                Text(title)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(.white)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 25)
            .background(
                RoundedRectangle(cornerRadius: 15)
                    .fill(Color.white.opacity(0.1))
            )
        }
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}
