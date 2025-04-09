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
import android.graphics.Color
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.locationtech.proj4j.CRSFactory
import org.locationtech.proj4j.CoordinateTransformFactory
import org.locationtech.proj4j.ProjCoordinate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
            showNotification(this, "No data")
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
        showNotification(this,"No data")

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
            AlertDialog.Builder(this)
                .setTitle("Help")
                .setMessage("📍 Tap 'Add Station' to add a new stop.\n\nStations nearby (within 1000m) will automatically appear in your notification. If departure times differ from their scheduled time they are marked red.")
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
            val jsonPayload = """{"query": "$query", "limit": 4}"""
            val requestBody = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(url).post(requestBody).build()
            val client = OkHttpClient()
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
            val client = OkHttpClient()
            return withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.use {
                    if (it.isSuccessful) {
                        val jsonData = JsonParser.parseString(it.body?.string())
                        val departures = jsonData.asJsonObject.get("Departures").asJsonArray
                        val resultBuilder = StringBuilder()
                        for (departure in departures) {
                            val departure_data = departure.asJsonObject

                            var time = departure_data.get("ScheduledTime").asString

                            var timeChanged = false
                            if(departure_data.get("RealTime") != null) {
                                if(departure_data.get("RealTime").asString != time){
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

                                if(timeChanged){
                                    resultBuilder.append("${departure.asJsonObject.get("LineName").asString} ${departure.asJsonObject.get("Direction").asString}: <font color='#FF5722'>${minutes}m${seconds}s</font>, ")
                                }else{
                                    resultBuilder.append("${departure.asJsonObject.get("LineName").asString} ${departure.asJsonObject.get("Direction").asString}: ${minutes}m${seconds}s, ")
                                }
                            }
                        }
                        return@withContext resultBuilder.toString()
                    } else {
                        return@withContext "Error: Unsuccessful response"
                    }
                }
            }
        }

        fun refreshNotification(context: Context) {
            val serviceIntent = Intent(context, ForegroundService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }

        fun showNotification(context: Context, content: String) {
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

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setAutoCancel(true)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true) // Makes it permanent
                .setCustomContentView(collapsedView)
                .setCustomBigContentView(expandedView)
                .build()
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(1, notification)
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
                MainActivity.refreshNotification(context)
            }
        }
    }
}


class StationSearchAdapter(
    private val stations: List<Station>,
    private val onItemClick: (Station) -> Unit
) : RecyclerView.Adapter<StationSearchAdapter.StationViewHolder>() {

    class StationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textView: TextView = itemView.findViewById(android.R.id.text1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StationViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        return StationViewHolder(view)
    }

    override fun onBindViewHolder(holder: StationViewHolder, position: Int) {
        val station = stations[position]
        // Display the station name (you could append the id or position if desired)
        holder.textView.text = station.name
        holder.itemView.setOnClickListener { onItemClick(station) }
    }

    override fun getItemCount(): Int = stations.size
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        // Start foreground with a lightweight notification
        startForeground(1, createNotification(applicationContext, "Refreshing..."))

        CoroutineScope(Dispatchers.IO).launch {

            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(applicationContext)

            if (ActivityCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                println("Fail: Location permission not granted")
            }

            val stationDao = AppDatabase.getDatabase(applicationContext).stationDao()
            val allStations = stationDao.getAllStations()

            val request = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                1000L
            ).setMinUpdateIntervalMillis(1000L).build()

            var updateCount = 0
            val maxUpdates = 10
            val accuracyThreshold = 100.0f

            val callback = object : LocationCallback() {
                override fun onLocationResult(locresult: LocationResult) {
                    val location = locresult.lastLocation
                    updateCount++

                    if (location != null) {
                        val acc = location.accuracy

                        if(acc > accuracyThreshold){
                            createAndShowNotification(applicationContext, "Accuracy: $accuracyThreshold")
                        }

                        if (acc <= accuracyThreshold || updateCount >= maxUpdates) {
                            fusedLocationClient.removeLocationUpdates(this)
                            Log.d("LocationUpdate", "✅ Stopped (acc <= $accuracyThreshold or max updates reached)")

                            CoroutineScope(Dispatchers.IO).launch {
                                var result = ""
                                for (station in allStations) {
                                    val stationLocation = Location("").apply {
                                        latitude = station.latitude
                                        longitude = station.longitude
                                    }

                                    if(location.distanceTo(stationLocation) < 1000){
                                        result += "<u>${station.name}</u>: " + sendPost(station.id)
                                    }
                                }
                                val currentTimeMillis = System.currentTimeMillis()
                                val date = Date(currentTimeMillis)
                                val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                                val formattedTime = formatter.format(date) + " "
                                withContext(Dispatchers.Main) {
                                    createAndShowNotification(applicationContext, formattedTime + result)
                                }
                            }

                            // 👉 You can now use this location to filter stations, etc.
                        }
                    } else {
                        Log.w("LocationUpdate", "⚠️ Received null location")
                    }
                }
            }
            fusedLocationClient.requestLocationUpdates(
                request,
                callback,
                Looper.getMainLooper()
            )
        }

        // Optional: auto-stop after some time if refreshNotification doesn't call stop
        Handler(Looper.getMainLooper()).postDelayed({
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
            //createAndShowNotification(applicationContext, "Outdated station info")
        }, 15_000) // stop after 30 seconds as a safety

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun createAndShowNotification(context: Context, content: String){
        val notification = createNotification(context, content)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, notification)
    }

    private fun createNotification(context: Context, content: String): Notification {
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
            .setAutoCancel(true)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true) // Makes it permanent
            .setCustomContentView(collapsedView)
            .setCustomBigContentView(expandedView)
            .build()
    }

}
