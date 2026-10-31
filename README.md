# Apophis Simulator Android

Aplicación Android nativa Kotlin. Versión experimental **0.2.0**.

## Implementado
- Integrador de velocidad Verlet para una partícula de prueba bajo gravedad solar y ocho planetas.
- Visualización orbital espacial con proyección ortográfica inclinada y zoom.
- Controles de fecha y distancia Tierra-Apophis aproximada.
- Descarga de elementos osculadores desde NASA/JPL SBDB API y caché local para uso sin conexión.
- GitHub Actions compila un APK debug descargable en la pestaña Actions.

## Limitaciones científicas
- Los planetas siguen órbitas **circulares y coplanares aproximadas**, sin efemérides de alta precisión.
- Los elementos de JPL son osculadores en su época; propagarlos con este modelo aproximado **no reproduce Horizons**.
- La distancia calculada **no puede utilizarse para determinar el acercamiento de 2029**, probabilidades de impacto ni regiones de colisión.
- No se modela la Luna, relatividad, perturbaciones no gravitatorias, incertidumbre orbital ni covarianzas.
- La visualización es una proyección 3D en Canvas, no un motor OpenGL.
- El fallback orbital es ilustrativo; pulsa 'Actualizar JPL' con conexión para descargar elementos reales.

**Apophis no tiene un impacto previsto con la Tierra en 2029.** Los escenarios de impacto serán módulos hipotéticos separados.

## Compilación
GitHub Actions ejecuta Gradle y publica `apophis-simulator-debug-apk` si la compilación pasa.
