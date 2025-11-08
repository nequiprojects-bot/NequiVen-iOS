#!/usr/bin/env python3
"""
Script de prueba LOCAL para verificar que los archivos Nequi funcionan correctamente
NO requiere Android, solo Python + Pillow + pytz
"""

import os
import sys

def test_nequi_voucher():
    """Probar generación de comprobante Nequi localmente"""
    
    print("="*70)
    print("🧪 TEST: Generación Comprobante Nequi")
    print("="*70)
    print()
    
    # Agregar assets al path
    assets_path = os.path.join(os.path.dirname(__file__), "app/src/main/assets")
    if not os.path.exists(assets_path):
        print(f"❌ No se encuentra: {assets_path}")
        return False
    
    sys.path.insert(0, assets_path)
    
    # Verificar archivos necesarios
    print("📋 Verificando archivos...")
    archivos_requeridos = [
        "config_nequi.py.backup",
        "utils_nequi.py.backup",
        "localgen_nequi.py.backup",
        "plantilla_nequi.jpg.backup",
        "fuentes/Manrope-Medium.ttf.backup"
    ]
    
    for archivo in archivos_requeridos:
        ruta = os.path.join(assets_path, archivo)
        existe = os.path.exists(ruta)
        emoji = "✅" if existe else "❌"
        print(f"  {emoji} {archivo}")
        if not existe:
            print(f"     Buscando en: {ruta}")
            return False
    
    print()
    print("📦 Importando módulos...")
    
    # Renombrar archivos temporalmente para importar
    import shutil
    temp_files = []
    try:
        # Crear copias sin .backup para importar
        for src_name, dst_name in [
            ("config_nequi.py.backup", "config_nequi.py"),
            ("utils_nequi.py.backup", "utils_nequi.py"),
            ("localgen_nequi.py.backup", "localgen_nequi.py"),
            ("plantilla_nequi.jpg.backup", "plantilla_nequi.jpg"),
            ("fuentes/Manrope-Medium.ttf.backup", "fuentes/Manrope-Medium.ttf")
        ]:
            src = os.path.join(assets_path, src_name)
            dst = os.path.join(assets_path, dst_name)
            if os.path.exists(src) and not os.path.exists(dst):
                shutil.copy2(src, dst)
                temp_files.append(dst)
                print(f"  ✅ Copiado: {dst_name}")
        
        # Importar módulos
        import config_nequi
        import utils_nequi
        import localgen_nequi
        
        print()
        print("🧪 Probando funciones de utils_nequi...")
        
        # Test format_currency
        test_amount = 50000.50
        formatted = utils_nequi.format_currency(test_amount)
        print(f"  format_currency({test_amount}) = '{formatted}'")
        assert "50.000,50" in formatted, "Error en format_currency"
        
        # Test generate_nequi_reference
        ref = utils_nequi.generate_nequi_reference()
        print(f"  generate_nequi_reference() = '{ref}'")
        assert ref.startswith("M"), "Referencia debe empezar con M"
        assert ref[1] in "2468", f"Segundo caracter debe ser par, es: {ref[1]}"
        assert len(ref) == 9, f"Referencia debe tener 9 caracteres, tiene: {len(ref)}"
        
        # Test format_phone
        phone = "3186724165"
        formatted_phone = utils_nequi.format_phone(phone)
        print(f"  format_phone('{phone}') = '{formatted_phone}'")
        assert formatted_phone == "318 672 4165", "Error en format_phone"
        
        # Test get_current_date_colombia
        fecha = utils_nequi.get_current_date_colombia()
        print(f"  get_current_date_colombia() = '{fecha}'")
        assert " de " in fecha, "Fecha debe contener ' de '"
        assert "a las" in fecha or " p. m." in fecha or " a. m." in fecha, "Fecha debe tener hora"
        
        print()
        print("🎨 Generando comprobante de prueba...")
        
        # Generar comprobante
        test_data = {
            "nombre": "Esmeralda Caraballo",
            "telefono": "3186724165",
            "monto": 5.00
        }
        
        output = localgen_nequi.generate_nequi_voucher(
            assets_path,
            test_data["nombre"],
            test_data["telefono"],
            test_data["monto"]
        )
        
        if os.path.exists(output):
            size = os.path.getsize(output)
            print(f"  ✅ Comprobante generado: {output}")
            print(f"  📦 Tamaño: {size:,} bytes")
            
            # Verificar dimensiones
            from PIL import Image
            img = Image.open(output)
            print(f"  📐 Dimensiones: {img.size[0]}x{img.size[1]}")
            img.close()
            
            print()
            print("="*70)
            print("✅ TODAS LAS PRUEBAS PASARON")
            print("="*70)
            print()
            print(f"📄 Comprobante de prueba guardado en:")
            print(f"   {output}")
            print()
            return True
        else:
            print(f"  ❌ No se generó el comprobante")
            return False
            
    except Exception as e:
        print(f"\n❌ ERROR: {e}")
        import traceback
        traceback.print_exc()
        return False
    finally:
        # Limpiar archivos temporales
        print("\n🧹 Limpiando archivos temporales...")
        for temp_file in temp_files:
            try:
                if os.path.exists(temp_file):
                    os.remove(temp_file)
                    print(f"  🗑️  Eliminado: {os.path.basename(temp_file)}")
            except:
                pass


if __name__ == "__main__":
    print()
    resultado = test_nequi_voucher()
    sys.exit(0 if resultado else 1)
