package com.example.lockscreentransitapp

import android.Manifest
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Html
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.RemoteViews
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.lockscreentransitapp.MainActivity.Companion.ACTION_SHOW_HELLO_WORLD
import com.example.lockscreentransitapp.MainActivity.Companion.CHANNEL_ID
import com.example.lockscreentransitapp.MainActivity.Companion.sendPost
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.locationtech.proj4j.CRSFactory
import org.locationtech.proj4j.CoordinateTransformFactory
import org.locationtech.proj4j.ProjCoordinate
import java.io.IOException
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.exp

@Entity(tableName = "stations")
data class Station(
    @PrimaryKey val id: String, // Unique station identifier; can be a code or UID from your transit API
    val name: String,
    val latitude: Double,
    val longitude: Double,
    // Optionally, add other fields like type, route information, etc.
)

@Dao
interface StationDao {

    // Retrieve all stations; LiveData allows the UI to observe changes automatically.
    @Query("SELECT * FROM stations")
    fun getAllStations(): List<Station>

    // Insert a single station. If a station with the same ID exists, replace it.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStation(station: Station)

    // Bulk insert multiple stations.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStations(stations: List<Station>)

    // Delete a station.
    @Delete
    suspend fun deleteStation(station: Station)
}

@Database(entities = [Station::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun stationDao(): StationDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            // If the INSTANCE is not null, then return it, else create the database instance in a thread-safe manner.
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "stations_database"
                )
                    .fallbackToDestructiveMigration() // Use a proper migration strategy in production apps.
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var button : Button;
    private val stationList = mutableListOf<Station>()
    private lateinit var adapter: ItemsAdapter
    private lateinit var recyclerView: RecyclerView

    // Database components
    private lateinit var db: AppDatabase
    private lateinit var stationDao: StationDao

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        if (allGranted) {
            createNotificationChannel()
            NotificationHelper.createAndShowNotification(this, "No data")
        } else {
            Toast.makeText(this, "Permissions must be granted", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        permissionLauncher.launch(permissions.toTypedArray())

        // Create the notification channel
        createNotificationChannel()
        NotificationHelper.createAndShowNotification(this, "No data")

        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recycler)
        button = findViewById(R.id.button)

        adapter = ItemsAdapter(stationList) { stationToRemove ->
            lifecycleScope.launch(Dispatchers.IO) {
                stationDao.deleteStation(stationToRemove)
                loadStations()
            }
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // Get database instance and DAO
        db = AppDatabase.getDatabase(this)
        stationDao = db.stationDao()

        loadStations()

        button.setOnClickListener {
            showAddStationDialog()
        }

        val helpButton = findViewById<ImageButton>(R.id.helpButton)

        helpButton.setOnClickListener {
            // Build an HTML string using <b> for bold and <br/> for line breaks
            val htmlMessage = """
        <b>Add Stations</b><br/>
        Tap <b>Add Station</b>, enter part of a stop name, then <b>Search</b>.<br/>
        Select one of the up-to-5 results and hit <b>Add Station</b> to save it.<br/><br/>
        
        <b>Remove Stations</b><br/>
        In the main list, tap the remove button next to any station to delete it.<br/><br/>
        
        <b>Persistent Notification</b><br/>
        The app runs a foreground service with an ongoing notification.<br/>
        • Shows up to 4 saved stations.<br/>
        • Tap the notification to manually refresh.<br/>
        • If GPS is enabled, the 4 closest stations are displayed.<br/>
        • Delayed or updated times are highlighted in red.
    """.trimIndent()

            // Convert HTML to a Spanned
            val spanned =
                Html.fromHtml(htmlMessage, Html.FROM_HTML_MODE_LEGACY)

            // Show the AlertDialog with styled text
            AlertDialog.Builder(this)
                .setTitle("Help & Features")
                .setMessage(spanned)
                .setPositiveButton("Got it") { dialog, _ -> dialog.dismiss() }
                .show()
        }


    }

    private fun loadStations() {
        lifecycleScope.launch(Dispatchers.IO) {
            val stations = stationDao.getAllStations()
            withContext(Dispatchers.Main) {
                stationList.clear()
                stationList.addAll(stations)
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun showAddStationDialog() {
        // Inflate the custom dialog layout
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_station, null)
        val editStationName = dialogView.findViewById<EditText>(R.id.editStationName)
        val btnSearch = dialogView.findViewById<Button>(R.id.btnSearch)
        val recyclerSearchResults = dialogView.findViewById<RecyclerView>(R.id.recyclerSearchResults)
        val btnAdd = dialogView.findViewById<Button>(R.id.btnAdd)

        // Initially disable the Add button until a station is selected
        btnAdd.isEnabled = false

        // A local list to hold API search results
        val searchResults = mutableListOf<Station>()
        var selectedStation: Station? = null

        // Set up the RecyclerView adapter
        val searchAdapter = StationSearchAdapter(searchResults) { station ->
            // When a station is clicked, store the selection and enable the Add button
            selectedStation = station
            btnAdd.isEnabled = true
            println("$station")
        }
        recyclerSearchResults.layoutManager = LinearLayoutManager(this)
        recyclerSearchResults.adapter = searchAdapter

        // Create the AlertDialog
        val dialog = AlertDialog.Builder(this)
            .setTitle("Add Station")
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .create()

        // Handle search button: call the API using the entered name
        btnSearch.setOnClickListener {
            val query = editStationName.text.toString().trim()
            if (query.isNotEmpty()) {
                // For network calls, use a coroutine on the IO dispatcher
                lifecycleScope.launch(Dispatchers.IO) {
                    // Call your API function. Adjust getStationID to return a list of Station objects.
                    val results: List<Station> = getStationID(query)
                    withContext(Dispatchers.Main) {
                        // Update the adapter with the new results
                        searchResults.clear()
                        searchResults.addAll(results)
                        searchAdapter.notifyDataSetChanged()
                        if (results.isEmpty()) {
                            Toast.makeText(this@MainActivity, "No stations found", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        // Handle the Add button: insert the selected station into the database
        btnAdd.setOnClickListener {
            selectedStation?.let { station ->
                lifecycleScope.launch(Dispatchers.IO) {
                    // Insert the station using your DAO (assumed to be set up)
                    stationDao.insertStation(station)
                    // Optionally, refresh your station list on the main view
                    loadStations()
                }
            }
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Permanent Notification Channel",
                NotificationManager.IMPORTANCE_HIGH
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_SHOW_HELLO_WORLD = "com.example.ACTION_SHOW_HELLO_WORLD"
        const val CHANNEL_ID  = "lock_screen_channel"

        suspend fun getStationID(query: String): List<Station> {
            val url = "https://webapi.vvo-online.de/tr/pointfinder"
            val jsonPayload = """{"query": "$query", "limit": 5}"""
            val requestBody = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(url).post(requestBody).build()
            val client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS)
                .writeTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .callTimeout(2, TimeUnit.SECONDS).build()

            return withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.use {
                    if (it.isSuccessful) {
                        val jsonData = JsonParser.parseString(it.body?.string()).asJsonObject
                        val retVal = mutableListOf<Station>()
                        if(jsonData.get("Points") == null){
                            return@withContext emptyList()
                        }
                        for(station in jsonData.get("Points").asJsonArray){
                            val station_string = station.asString
                            val parts = station_string.split("|")
                            val id = parts[0]
                            val name = parts[3]
                            val gkRight = parts[5].toDouble()
                            val gkUp = parts[4].toDouble()
                            val (lat,lon) = gk4ToWgs84(gkRight, gkUp)
                            val station_object = Station(id, name, lat, lon)
                            retVal.add(station_object)
                        }
                        return@withContext retVal
                    } else {
                        return@withContext emptyList()
                    }
                }
            }
        }

        suspend fun sendPost(id: String): String {
            val url = "https://webapi.vvo-online.de/dm"
            val jsonPayload = """{"stopid": "$id", "limit": 4}"""
            val requestBody = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(url).post(requestBody).build()
            val client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS)
                .writeTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .callTimeout(2, TimeUnit.SECONDS).build()
            return withContext(Dispatchers.IO) {
                withTimeout(2000L) {
                    val response = client.newCall(request).execute()
                    response.use {
                        if (!it.isSuccessful) {
                            return@withTimeout "Error: ${it.code}"
                        }
                        val jsonData = JsonParser.parseString(it.body?.string())
                        val departures = jsonData.asJsonObject.get("Departures").asJsonArray
                        val resultBuilder = StringBuilder()
                        for (departure in departures) {
                            val departure_data = departure.asJsonObject
                            var time = departure_data.get("ScheduledTime").asString
                            var timeChanged = false
                            if (departure_data.get("RealTime") != null) {
                                if (departure_data.get("RealTime").asString != time) {
                                    timeChanged = true
                                }
                                time = departure_data.get("RealTime").asString
                            }
                            val regex = """/Date\((\d+)([+-]\d{4})?\)/""".toRegex()
                            val matchResult = regex.find(time)
                            if (matchResult != null) {
                                val timestampMillis = matchResult.groupValues[1].toLong()
                                val currentTimeMillis = System.currentTimeMillis()
                                val differenceMillis = timestampMillis - currentTimeMillis
                                val totalSeconds = differenceMillis / 1000
                                val minutes = totalSeconds / 60
                                val seconds = totalSeconds % 60

                                if (timeChanged) {
                                    resultBuilder.append(
                                        "${departure.asJsonObject.get("LineName").asString} ${
                                            departure.asJsonObject.get(
                                                "Direction"
                                            ).asString
                                        }: <font color='#FF5722'>${minutes}m${seconds}s</font>, "
                                    )
                                } else {
                                    resultBuilder.append(
                                        "${departure.asJsonObject.get("LineName").asString} ${
                                            departure.asJsonObject.get(
                                                "Direction"
                                            ).asString
                                        }: ${minutes}m${seconds}s, "
                                    )
                                }
                            }
                        }
                        return@withTimeout resultBuilder.toString()
                    }
                }
            }
        }
    }

    // RecyclerView Adapter class for displaying the list of items
    inner class ItemsAdapter(private val items: List<Station>, private val onRemoveClick: (Station) -> Unit) :
        RecyclerView.Adapter<ItemsAdapter.ItemViewHolder>() {

        // ViewHolder representing each item in the list
        inner class ItemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textView: TextView = itemView.findViewById(R.id.textStationName)
            val btnRemove: Button = itemView.findViewById(R.id.btnRemove)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
            // Inflate a simple list item layout provided by Android
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_station, parent, false)
            return ItemViewHolder(view)
        }

        override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
            // Bind the text data to the TextView for each list item
            holder.textView.text = items[position].name

            val station = items[position]

            holder.btnRemove.setOnClickListener {
                onRemoveClick(station)
            }
        }

        override fun getItemCount(): Int = items.size
    }

}

// BroadcastReceiver to handle notification click
class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if(intent?.action == MainActivity.ACTION_SHOW_HELLO_WORLD){
            if(context != null){
                val serviceIntent = Intent(context, ForegroundService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}


class StationSearchAdapter(
    private val stations: List<Station>,
    private val onItemClick: (Station) -> Unit
) : RecyclerView.Adapter<StationSearchAdapter.StationViewHolder>() {

    // track which position is selected
    private var selectedPosition = RecyclerView.NO_POSITION

    inner class StationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textView: TextView = itemView.findViewById(android.R.id.text1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StationViewHolder {
        // use the “activated” list item so Android gives you a ripple + activated highlight
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_activated_1, parent, false)
        return StationViewHolder(view)
    }

    override fun getItemCount(): Int = stations.size

    override fun onBindViewHolder(holder: StationViewHolder, position: Int) {
        val station = stations[position]
        holder.textView.text = station.name

        val bgColor = if (position == selectedPosition)
            holder.itemView.context.getColor(android.R.color.darker_gray)
        else
            Color.TRANSPARENT
        holder.itemView.setBackgroundColor(bgColor)

        holder.itemView.setOnClickListener {
            // 2) update the selection
            val previous = selectedPosition
            selectedPosition = holder.bindingAdapterPosition
            notifyItemChanged(previous)
            notifyItemChanged(selectedPosition)

            // 3) callback for your dialog logic
            onItemClick(station)
        }
    }
}




fun gk4ToWgs84(rightGK: Double, upGK: Double): Pair<Double, Double> {
    val crsFactory = CRSFactory()
    // GK zone 4 (EPSG:31468), based on Bessel 1841 ellipsoid
    val gk4 = crsFactory.createFromName("EPSG:31468")
    val wgs84 = crsFactory.createFromName("EPSG:4326")
    val transform = CoordinateTransformFactory().createTransform(gk4, wgs84)
    val srcCoord = ProjCoordinate(rightGK, upGK)
    val dstCoord = ProjCoordinate()
    transform.transform(srcCoord, dstCoord)
    return Pair(dstCoord.y, dstCoord.x) // (lat, lon)
}



class ForegroundService : Service() {

    companion object {
        private const val NOTIF_ID = 1
        private const val UPDATE_INTERVAL_MS = 1000L
        private const val MAX_UPDATES = 5
        private const val ACCURACY_THRESHOLD = 100f
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val departureCache = mutableMapOf<String, String>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        // 1) Kick off your permanent notification
        startForeground(
            NOTIF_ID,
            NotificationHelper.createNotification(
                applicationContext,
                "Loading stations…"
            )
        )

        departureCache.clear()

        serviceScope.launch {
            // 2) Load all stations once
            val stationDao = AppDatabase.getDatabase(applicationContext).stationDao()
            val allStations = stationDao.getAllStations()

            // 3) Display the first 4 stations immediately
            var displayed = allStations.take(4)
            displayStationTimes(displayed)

            // 4) Set up location requests
            val fusedClient =
                LocationServices.getFusedLocationProviderClient(applicationContext)

            // Permission guard
            if (ActivityCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e("ForegroundService", "Location permission not granted")
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
                return@launch
            }

            val req = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                UPDATE_INTERVAL_MS
            ).setMinUpdateIntervalMillis(UPDATE_INTERVAL_MS)
                .build()

            var updates = 0

            // 5) Callback on every location
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation ?: return
                    updates++

                    // a) Resort & pick top 4
                    val top4 = allStations
                        .sortedBy { st ->
                            Location("").apply {
                                latitude = st.latitude
                                longitude = st.longitude
                            }.distanceTo(loc)
                        }
                        .take(4)

                    // b) If order changed, re-display
                    if (top4.map { it.id } != displayed.map { it.id }) {
                        displayed = top4
                        serviceScope.launch { displayStationTimes(displayed) }
                    }

                    // c) Stop if accurate enough or max tries reached
                    if (loc.accuracy <= ACCURACY_THRESHOLD || updates >= MAX_UPDATES) {
                        fusedClient.removeLocationUpdates(this)
                        stopForeground(STOP_FOREGROUND_DETACH)
                        stopSelf()
                    }
                }
            }

            fusedClient.requestLocationUpdates(
                req,
                callback,
                Looper.getMainLooper()
            )
        }

        // 6) Safety net: stop the service after ~10 s
        Handler(Looper.getMainLooper()).postDelayed({
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
            serviceScope.cancel("Stopped after 10 seconds")
        }, UPDATE_INTERVAL_MS * MAX_UPDATES * 2)

        return START_NOT_STICKY
    }



    private suspend fun displayStationTimes(stations: List<Station>) {
        if (stations.isEmpty()) {
            NotificationHelper.createAndShowNotification(
                applicationContext,
                "No stations saved"
            )
            return
        }

        // Only fetch the ones we haven’t already cached
        val toFetch = stations.filter { !departureCache.containsKey(it.id) }

        for (st in toFetch) {
            val times = try {
                sendPost(st.id)    // this will now time out after 2 s
            } catch (e: Exception) {
                NotificationHelper.createAndShowNotification(
                    applicationContext,
                    "Error: ${e.message}"
                )
                return
            }
            departureCache[st.id] = times
        }

        // Build the HTML from cache and display
        val sb = StringBuilder()
        stations.forEachIndexed { idx, st ->
            val times = departureCache[st.id] ?: ""
            sb.append("<u>${st.name}</u>: $times")
            if (idx < stations.lastIndex) sb.append(" ")
        }

        NotificationHelper.createAndShowNotification(
            applicationContext,
            sb.toString()
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

}

object NotificationHelper {

    fun createNotification(context: Context, content: String): Notification {
        val spannedText = Html.fromHtml(content, Html.FROM_HTML_MODE_COMPACT)
        // Intent to handle notification click
        val intent = Intent(context, NotificationReceiver::class.java).apply {
            action = ACTION_SHOW_HELLO_WORLD
        }
        val pendingIntent: PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isDarkMode = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val color = if (isDarkMode) Color.WHITE else Color.BLACK

        val collapsedView = RemoteViews(context.packageName, R.layout.notification_collapsed)
        collapsedView.setTextColor(R.id.notificationTitle, color)
        collapsedView.setTextViewText(R.id.notificationTitle, spannedText)
        collapsedView.setOnClickPendingIntent(R.id.notificationTitle, pendingIntent)

        val expandedView = RemoteViews(context.packageName, R.layout.notification_expanded)
        expandedView.setTextColor(R.id.notificationTitleExpanded, color)
        expandedView.setTextViewText(R.id.notificationTitleExpanded, spannedText)
        expandedView.setOnClickPendingIntent(R.id.notificationTitleExpanded, pendingIntent)

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setAutoCancel(false)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true) // Makes it permanent
            .setCustomContentView(collapsedView)
            .setCustomBigContentView(expandedView)
            .build()
    }

    fun createAndShowNotification(context: Context, content: String){
        val notification = createNotification(context, content)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, notification)
    }

}
