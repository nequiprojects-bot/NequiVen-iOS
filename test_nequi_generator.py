#!/usr/bin/env python3
"""Test generador Nequi"""
import sys
import os

# Agregar assets al path
assets_path = "app/src/main/assets"
sys.path.insert(0, assets_path)

# Limpiar módulos
for mod in ['config_nequi', 'utils_nequi', 'localgen_nequi']:
    if mod in sys.modules:
        del sys.modules[mod]

# Importar
import localgen_nequi

# Test
try:
    result = localgen_nequi.generate_nequi_voucher(
        assets_path=assets_path,
        nombre="Cristian Lopez",
        telefono="3186724165",
        monto=5000.00
    )
    print(f"\n✅ SUCCESS: {result}")
except Exception as e:
    print(f"\n❌ ERROR: {e}")
    import traceback
    traceback.print_exc()
