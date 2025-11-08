#!/bin/bash

# ============================================
# Script para compilar Nequi Ven para iOS
# ============================================

set -e  # Detener si hay errores

echo "🚀 Iniciando compilación iOS de Nequi Ven..."
echo ""

# Verificar que estamos en Mac
if [[ "$OSTYPE" != "darwin"* ]]; then
    echo "❌ ERROR: Este script debe ejecutarse en macOS"
    echo "   iOS solo puede compilarse en Mac con Xcode instalado"
    exit 1
fi

# Verificar que Xcode esté instalado
if ! command -v xcodebuild &> /dev/null; then
    echo "❌ ERROR: Xcode no está instalado"
    echo "   Instala Xcode desde: https://apps.apple.com/app/xcode/id497799835"
    exit 1
fi

# Verificar que CocoaPods esté instalado
if ! command -v pod &> /dev/null; then
    echo "❌ ERROR: CocoaPods no está instalado"
    echo "   Instala con: sudo gem install cocoapods"
    exit 1
fi

echo "✅ Verificaciones completadas"
echo ""

# 1. Compilar módulo shared para iOS
echo "📦 Compilando módulo compartido (KMM)..."
./gradlew :shared:podPublishXCFramework
echo "✅ Framework compartido generado"
echo ""

# 2. Instalar dependencias de CocoaPods
echo "📦 Instalando dependencias iOS (CocoaPods)..."
cd iosApp
pod install
cd ..
echo "✅ Dependencias instaladas"
echo ""

# 3. Compilar proyecto iOS
echo "🔨 Compilando proyecto iOS..."
xcodebuild -workspace iosApp/iosApp.xcworkspace \
           -scheme iosApp \
           -configuration Debug \
           -destination 'platform=iOS Simulator,name=iPhone 14' \
           build
echo "✅ Compilación iOS exitosa"
echo ""

echo "🎉 ¡Listo! La app iOS está compilada"
echo ""
echo "Para abrir en Xcode:"
echo "   open iosApp/iosApp.xcworkspace"
echo ""
