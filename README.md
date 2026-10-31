# Apophis Simulator Android

Aplicación Android nativa en Kotlin. **Estado: prototipo preliminar.**

La versión actual calcula la energía cinética de un impacto *hipotético* usando un modelo de dos cuerpos; **no predice un impacto de Apophis en 2029**. El motor N-cuerpos, la descarga JPL y el visor 3D están pendientes.

## Compilar

GitHub Actions ejecuta `gradle :app:assembleDebug` y publica el APK de depuración como artefacto si la compilación tiene éxito. El APK de depuración no está destinado a distribución pública.
