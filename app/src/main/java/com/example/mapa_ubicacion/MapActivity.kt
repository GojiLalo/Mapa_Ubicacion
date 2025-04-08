package com.example.mapa_ubicacion

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import kotlin.concurrent.thread

class MapActivity : AppCompatActivity() {

    private lateinit var mapWebView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private val locationPermissionCode = 100
    private var userLatitude = 0.0
    private var userLongitude = 0.0
    private var locationUpdateInterval = 10000L // 10 segundos
    private var searchRadius = 1000 // radio de búsqueda en metros

    // Categorías de POI a buscar
    private val poiCategories = listOf(
        "tourism=museum",
        "tourism=attraction",
        "leisure=park",
        "amenity=restaurant",
        "amenity=cafe"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)
        mapWebView = findViewById(R.id.mapWebView)
        progressBar = findViewById(R.id.progressBar)
        setupWebView()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    userLatitude = location.latitude
                    userLongitude = location.longitude
                    updateMapWithLocation(userLatitude, userLongitude)
                    fetchNearbyPOIs(userLatitude, userLongitude)
                }
            }
        }
        checkLocationPermissions()
    }

    private fun setupWebView() {
        mapWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setGeolocationEnabled(true)
            cacheMode = WebSettings.LOAD_NO_CACHE
            // Optimizar rendimiento
            setRenderPriority(WebSettings.RenderPriority.HIGH)
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        // Añadir interfaz JavaScript para comunicación
        mapWebView.addJavascriptInterface(WebAppInterface(), "AndroidInterface")

        mapWebView.webChromeClient = WebChromeClient()
        mapWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                // Buscar POIs cuando el mapa termina de cargar
                if (userLatitude != 0.0 && userLongitude != 0.0) {
                    fetchNearbyPOIs(userLatitude, userLongitude)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                progressBar.visibility = View.GONE
                Toast.makeText(
                    this@MapActivity,
                    "Error al cargar el mapa: ${error?.description}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun checkLocationPermissions() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                locationPermissionCode
            )
        } else {
            startLocationUpdates()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == locationPermissionCode) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates()
            } else {
                Toast.makeText(
                    this,
                    "Permiso de ubicación denegado. Usando ubicación predeterminada.",
                    Toast.LENGTH_LONG
                ).show()
                // Usar una ubicación predeterminada si no se conceden permisos
                updateMapWithLocation(40.416775, -3.703790) // Madrid como ejemplo
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        progressBar.visibility = View.VISIBLE

        // Obtener la última ubicación conocida
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    userLatitude = location.latitude
                    userLongitude = location.longitude
                    updateMapWithLocation(userLatitude, userLongitude)
                } else {
                    requestNewLocationData()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    "Error al obtener la ubicación: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                progressBar.visibility = View.GONE
                // Usar ubicación predeterminada en caso de error
                updateMapWithLocation(40.416775, -3.703790) // Madrid como ejemplo
            }
    }

    @SuppressLint("MissingPermission")
    private fun requestNewLocationData() {
        val locationRequest = LocationRequest.create().apply {
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            interval = locationUpdateInterval
            fastestInterval = locationUpdateInterval / 2
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun updateMapWithLocation(latitude: Double, longitude: Double) {
        val htmlContent = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                <title>Mi Ubicación</title>
                <link rel="stylesheet" href="https://unpkg.com/leaflet@1.7.1/dist/leaflet.css" />
                <script src="https://unpkg.com/leaflet@1.7.1/dist/leaflet.js"></script>
                <style>
                    body, html {
                        height: 100%;
                        margin: 0;
                        padding: 0;
                    }
                    #map {
                        width: 100%;
                        height: 100%;
                    }
                    .leaflet-popup-content {
                        max-width: 200px;
                    }
                </style>
            </head>
            <body>
                <div id="map"></div>
                
                <script>
                    // Inicializar el mapa
                    var map = L.map('map').setView([$latitude, $longitude], 15);
                    
                    // Añadir capa de OpenStreetMap
                    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
                        maxZoom: 19
                    }).addTo(map);
                    
                    // Añadir marcador en la ubicación actual
                    var userMarker = L.marker([$latitude, $longitude], {
                        icon: L.icon({
                            iconUrl: 'https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-blue.png',
                            shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/0.7.7/images/marker-shadow.png',
                            iconSize: [25, 41],
                            iconAnchor: [12, 41],
                            popupAnchor: [1, -34],
                            shadowSize: [41, 41]
                        })
                    }).addTo(map);
                    userMarker.bindPopup("<b>Mi ubicación</b>").openPopup();
                    
                    // Crear capa de grupo para puntos de interés
                    var poiLayer = L.layerGroup().addTo(map);
                    
                    // Función para actualizar la ubicación del marcador
                    function updateLocation(lat, lng) {
                        userMarker.setLatLng([lat, lng]);
                        map.setView([lat, lng], map.getZoom());
                        userMarker.bindPopup("<b>Mi ubicación</b><br>Lat: " + lat.toFixed(6) + "<br>Lng: " + lng.toFixed(6)).openPopup();
                    }
                    
                    // Función para añadir puntos de interés
                    function addPOIs(pois) {
                        // Limpiar capa existente
                        poiLayer.clearLayers();
                        
                        // Iconos específicos por categoría
                        var icons = {
                            'restaurant': L.icon({
                                iconUrl: 'https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-red.png',
                                shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/0.7.7/images/marker-shadow.png',
                                iconSize: [25, 41],
                                iconAnchor: [12, 41],
                                popupAnchor: [1, -34],
                                shadowSize: [41, 41]
                            }),
                            'cafe': L.icon({
                                iconUrl: 'https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-orange.png',
                                shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/0.7.7/images/marker-shadow.png',
                                iconSize: [25, 41],
                                iconAnchor: [12, 41],
                                popupAnchor: [1, -34],
                                shadowSize: [41, 41]
                            }),
                            'park': L.icon({
                                iconUrl: 'https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-green.png',
                                shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/0.7.7/images/marker-shadow.png',
                                iconSize: [25, 41],
                                iconAnchor: [12, 41],
                                popupAnchor: [1, -34],
                                shadowSize: [41, 41]
                            }),
                            'museum': L.icon({
                                iconUrl: 'https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-violet.png',
                                shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/0.7.7/images/marker-shadow.png',
                                iconSize: [25, 41],
                                iconAnchor: [12, 41],
                                popupAnchor: [1, -34],
                                shadowSize: [41, 41]
                            }),
                            'attraction': L.icon({
                                iconUrl: 'https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-gold.png',
                                shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/0.7.7/images/marker-shadow.png',
                                iconSize: [25, 41],
                                iconAnchor: [12, 41],
                                popupAnchor: [1, -34],
                                shadowSize: [41, 41]
                            }),
                            'default': L.icon({
                                iconUrl: 'https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-grey.png',
                                shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/0.7.7/images/marker-shadow.png',
                                iconSize: [25, 41],
                                iconAnchor: [12, 41],
                                popupAnchor: [1, -34],
                                shadowSize: [41, 41]
                            })
                        };
                        
                        // Añadir cada POI al mapa
                        for (var i = 0; i < pois.length; i++) {
                            var poi = pois[i];
                            var icon = icons[poi.category] || icons['default'];
                            
                            var marker = L.marker([poi.lat, poi.lon], {icon: icon}).addTo(poiLayer);
                            
                            // Crear contenido del popup
                            var popupContent = "<b>" + poi.name + "</b>";
                            if (poi.category) {
                                popupContent += "<br>Tipo: " + poi.category;
                            }
                            if (poi.distance) {
                                popupContent += "<br>Distancia: " + poi.distance.toFixed(0) + "m";
                            }
                            
                            marker.bindPopup(popupContent);
                        }
                    }
                    
                    // Función para mostrar un círculo de radio de búsqueda
                    function showSearchRadius(lat, lng, radius) {
                        if (window.searchRadiusCircle) {
                            map.removeLayer(window.searchRadiusCircle);
                        }
                        window.searchRadiusCircle = L.circle([lat, lng], {
                            color: 'rgba(0, 123, 255, 0.3)',
                            fillColor: 'rgba(0, 123, 255, 0.1)',
                            fillOpacity: 0.3,
                            radius: radius
                        }).addTo(map);
                    }
                </script>
            </body>
            </html>
        """.trimIndent()

        mapWebView.loadDataWithBaseURL(
            "https://openstreetmap.org",
            htmlContent,
            "text/html",
            "UTF-8",
            null
        )
    }

    // Función para buscar puntos de interés cercanos usando Overpass API
    private fun fetchNearbyPOIs(latitude: Double, longitude: Double) {
        thread {
            try {
                // Mostrar mensaje de inicio de búsqueda
                runOnUiThread {
                    Toast.makeText(
                        this@MapActivity,
                        "Buscando puntos de interés...",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                // Resto del código...

            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    // Mostrar error específico
                    val errorMsg = when {
                        e is java.net.SocketTimeoutException -> "Timeout al conectar con la API de Overpass"
                        e is java.net.UnknownHostException -> "Error de conexión a internet"
                        e.message?.contains("JSON") == true -> "Error al procesar datos de POIs"
                        else -> "Error al buscar POIs: ${e.message}"
                    }

                    Toast.makeText(this@MapActivity, errorMsg, Toast.LENGTH_LONG).show()

                    // Usar datos alternativos como fallback
                    fetchPredefinedPOIs(latitude, longitude)
                }
            }
        }
    }

    // Construir consulta Overpass
    private fun buildOverpassQuery(
        lat: Double,
        lon: Double,
        radius: Int,
        categories: List<String>
    ): String {
        val bbox = "$lat,$lon,$radius"
        val filters = categories.joinToString(" or ") { "node[$it]" }

        return """
            [out:json];
            (
              $filters(around:$radius,$lat,$lon);
            );
            out body;
        """.trimIndent().replace("\n", "")
    }

    // Parsear la respuesta de Overpass
    private fun parseOverpassResponse(
        response: String,
        userLat: Double,
        userLon: Double
    ): List<Map<String, Any>> {
        val result = mutableListOf<Map<String, Any>>()

        try {
            val jsonResponse = JSONObject(response)
            val elements = jsonResponse.getJSONArray("elements")

            for (i in 0 until elements.length()) {
                val element = elements.getJSONObject(i)

                if (element.getString("type") == "node") {
                    val lat = element.getDouble("lat")
                    val lon = element.getDouble("lon")
                    val tags = element.getJSONObject("tags")

                    val name = if (tags.has("name")) tags.getString("name") else "Sin nombre"

                    // Determinar categoría
                    val category = when {
                        tags.has("amenity") && tags.getString("amenity") == "restaurant" -> "restaurant"
                        tags.has("amenity") && tags.getString("amenity") == "cafe" -> "cafe"
                        tags.has("leisure") && tags.getString("leisure") == "park" -> "park"
                        tags.has("tourism") && tags.getString("tourism") == "museum" -> "museum"
                        tags.has("tourism") && tags.getString("tourism") == "attraction" -> "attraction"
                        else -> "default"
                    }

                    // Calcular distancia aproximada
                    val distance = calculateDistance(userLat, userLon, lat, lon)

                    val poi = mapOf(
                        "name" to name,
                        "lat" to lat,
                        "lon" to lon,
                        "category" to category,
                        "distance" to distance
                    )

                    result.add(poi)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return result
    }

    // Método simple para calcular la distancia entre dos puntos (fórmula Haversine)
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0 // metros

        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)

        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))

        return earthRadius * c
    }

    // Clase para comunicación JavaScript
    inner class WebAppInterface {
        @JavascriptInterface
        fun showToast(message: String) {
            runOnUiThread {
                Toast.makeText(this@MapActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Función JavaScript para actualizar el marcador en tiempo real
    fun updateMarkerPosition(latitude: Double, longitude: Double) {
        val jsCode = "updateLocation($latitude, $longitude);"
        mapWebView.evaluateJavascript(jsCode, null)
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startLocationUpdates()
        }
    }

    override fun onPause() {
        super.onPause()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    // Método alternativo: Usar datos predefinidos o una API propia en lugar de Overpass
    // Esto puede ser útil si Overpass está sobrecargado o si prefieres tus propios datos
    private fun fetchPredefinedPOIs(latitude: Double, longitude: Double) {
        // Lista de POIs de ejemplo (podrías cargarla de un archivo JSON o una base de datos)
        val predefinedPOIs = listOf(
            mapOf(
                "name" to "Restaurante Ejemplo",
                "lat" to (latitude + 0.003),
                "lon" to (longitude + 0.002),
                "category" to "restaurant"
            ),
            mapOf(
                "name" to "Museo Nacional",
                "lat" to (latitude - 0.002),
                "lon" to (longitude + 0.001),
                "category" to "museum"
            ),
            mapOf(
                "name" to "Parque Central",
                "lat" to (latitude + 0.001),
                "lon" to (longitude - 0.002),
                "category" to "park"
            ),
            mapOf(
                "name" to "Café del Centro",
                "lat" to (latitude - 0.001),
                "lon" to (longitude - 0.001),
                "category" to "cafe"
            ),
            mapOf(
                "name" to "Monumento Histórico",
                "lat" to (latitude + 0.002),
                "lon" to (longitude - 0.003),
                "category" to "attraction"
            )
        )

        // Calcular distancias y filtrar por radio
        val filteredPOIs = predefinedPOIs.map { poi ->
            val lat = poi["lat"] as Double
            val lon = poi["lon"] as Double
            val distance = calculateDistance(latitude, longitude, lat, lon)
            poi + mapOf("distance" to distance)
        }.filter { it["distance"] as Double <= searchRadius }

        // Actualizar el mapa
        runOnUiThread {
            val poiJson = JSONArray(filteredPOIs).toString()
            val jsCode = "addPOIs($poiJson);"
            mapWebView.evaluateJavascript(jsCode, null)
        }
    }
}