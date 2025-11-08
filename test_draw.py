#!/usr/bin/env python3
from PIL import Image, ImageDraw, ImageFont
import os

# Abrir plantilla
img = Image.open("app/src/main/assets/plantilla_nequi.jpg.backup").convert('RGB')
draw = ImageDraw.Draw(img)

print(f"Plantilla: {img.size}")

# Cargar fuente
font = ImageFont.truetype("app/src/main/assets/fuentes/Manrope-Medium.ttf", 22)

# Datos de prueba
datos = [
    ("nombre", "Cristian Lopez", (49, 628)),
    ("valor", "$ 2.500,00", (49, 719)),
    ("telefono", "318 672 4165", (49, 798)),
    ("fecha", "25 oct 2025 2:21 a.m.", (49, 888)),
    ("referencia", "M27214058", (49, 968)),
    ("disponible", "Disponible", (49, 1055))
]

# Dibujar TODOS
print("\n=== DIBUJANDO ===")
for field, text, pos in datos:
    draw.text(pos, text, font=font, fill="#000000")
    print(f"✓ {field}: '{text}' en {pos}")

# Guardar
output = "/tmp/test_nequi.png"
img.save(output, 'PNG')
print(f"\n✅ Guardado: {output}")
print(f"Tamaño: {os.path.getsize(output)} bytes")
print(f"\nAbre la imagen: {output}")
