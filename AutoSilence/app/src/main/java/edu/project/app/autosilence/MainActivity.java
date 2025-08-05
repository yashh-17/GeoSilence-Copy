package edu.project.app.autosilence;

import android.Manifest;

import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;
import androidx.appcompat.widget.Toolbar;
import androidx.appcompat.app.AlertDialog;


import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingClient;
import com.google.android.gms.location.GeofencingRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity implements OnCompleteListener<Void>, LocationListAdapter.RecyclerViewClickCallbacks {

    //Constant to determine the activity from which the result is returned.
    public static final String ACTIVITY_FROM = "activityFrom";

    //Constant key for Preference to store the request id count
    public static final String REQUEST_ID_EXTRA = "geofenceRequestId";

    //Constant tag for logging purpose.
    private static final String TAG = MainActivity.class.getSimpleName();

    Snackbar snackbar = null;

    //Geofencing client
    GeofencingClient geofencingClient;
    LocationLoadTask loadTask;

    //The recyclerview adapter for the locationList.
    private LocationListAdapter listAdapter;
    //Database instance to store the added geofences.
    private LocationDBHelper locationDBHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            //Setting main content
            setContentView(R.layout.activity_main);
            Toolbar toolbar = findViewById(R.id.toolbar);
            setSupportActionBar(toolbar);

            //Setting up floating action button
            FloatingActionButton fab = findViewById(R.id.fab);
            fab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Intent intent = new Intent(MainActivity.this, MapsActivity.class);
                    startActivityForResult(intent, 777);
                }
            });

            //Requesting the permissions
            requestPermissionsIfNeeded();

            //Requesting permission to change Audio Settings
            NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && notificationManager != null && !notificationManager.isNotificationPolicyAccessGranted()) {
                Intent intent = new Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                startActivity(intent);
            }

            //Initialising GeofencingClient
            geofencingClient = LocationServices.getGeofencingClient(this);

            //Preparing the recyclerview
            locationDBHelper = new LocationDBHelper(this);
            RecyclerView locationList = findViewById(R.id.rv_list_locations);
            locationList.setLayoutManager(new LinearLayoutManager(this));
            locationList.setAdapter((listAdapter = new LocationListAdapter(null)));
            //Register for RecyclerView Click Callback
            listAdapter.setRecyclerViewClickCallbacks(this);
            new ItemTouchHelper(listAdapter.getSwipeHelper()).attachToRecyclerView(locationList);
        } catch (Exception e) {
            Toast.makeText(this, Log.getStackTraceString(e), Toast.LENGTH_LONG).show();
            Log.e(TAG, "onCreate: An Exception occurred!", e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadDataIntoAdapter();
    }

    void loadDataIntoAdapter() {
        loadTask = new LocationLoadTask();
        loadTask.execute();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {

        try {
            //Started activity is completed with result code RESULT_OK. If not just ignore.
            if (resultCode == RESULT_OK) {
                String fromActivity = data.getStringExtra(ACTIVITY_FROM);

                //Check if the resulted activity is Maps activity or the ConfirmDialogActivity
                if (fromActivity != null && fromActivity.equals(MapsActivity.FROM_MAPS_ACTIVITY)) {
                    //Activity resulted is the MapsActivity.
                    //Retrieve selected latitude and longitude.
                    double lat = data.getDoubleExtra(ConfirmDialogActivity.GEO_LATITUDE, 0.0);
                    double lng = data.getDoubleExtra(ConfirmDialogActivity.GEO_LONGITUDE, 0.0);
                    float rad = 100.0f;
                    Intent intent = new Intent(this, ConfirmDialogActivity.class)
                            .putExtra(ConfirmDialogActivity.GEO_LATITUDE, lat)
                            .putExtra(ConfirmDialogActivity.GEO_LONGITUDE, lng)
                            .putExtra(ConfirmDialogActivity.GEO_RADIUS, rad);
                    startActivityForResult(intent, 777);

                } else {
                    //Activity resulted is the ConfirmDialogActivity.
                    Double lat = data.getDoubleExtra(ConfirmDialogActivity.GEO_LATITUDE, 0.0);
                    Double lng = data.getDoubleExtra(ConfirmDialogActivity.GEO_LONGITUDE, 0.0);
                    Float rad = data.getFloatExtra(ConfirmDialogActivity.GEO_RADIUS, 0.0f);
                    String name = data.getStringExtra(ConfirmDialogActivity.GEO_NAME);
                    String address = data.getStringExtra(ConfirmDialogActivity.GEO_ADDRESS);
                    int idCount = getPreferences(MODE_PRIVATE).getInt(REQUEST_ID_EXTRA, 0);
                    SharedPreferences.Editor prefEdit = getPreferences(MODE_PRIVATE).edit();
                    prefEdit.putInt(REQUEST_ID_EXTRA, idCount + 1).apply();
                    String requestId = REQUEST_ID_EXTRA + idCount;
                    //Adding selected location to the geofencing client
                    ArrayList<Geofence> geofenceList = new ArrayList<>();
                    geofenceList.add(new Geofence.Builder()
                            .setCircularRegion(lat, lng, rad)
                            .setRequestId(requestId)
                            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER | Geofence.GEOFENCE_TRANSITION_EXIT)
                            .setExpirationDuration(Geofence.NEVER_EXPIRE)
                            .build());
                    GeofencingRequest request = new GeofencingRequest.Builder()
                            .addGeofences(geofenceList)
                            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER).build();
                    try {
                        geofencingClient.addGeofences(request, getPendingIntent()).addOnCompleteListener(this);
                        locationDBHelper.insert(requestId, name, lat, lng, rad, address);
                    } catch (SecurityException e) {
                        Toast.makeText(this, Log.getStackTraceString(e), Toast.LENGTH_LONG).show();
                    }
                }
            }
            super.onActivityResult(requestCode, resultCode, data);
        } catch (Exception e) {
            Toast.makeText(this, Log.getStackTraceString(e), Toast.LENGTH_LONG).show();
        }
    }

    private PendingIntent getPendingIntent() {
        try {
            Intent intent = new Intent(this, GeofenceReceiver.class);
            return PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        } catch (Exception e) {
            Toast.makeText(this, Log.getStackTraceString(e), Toast.LENGTH_LONG).show();
        }
        return null;
    }

    @Override
    public void onComplete(@NonNull Task<Void> task) {
        if (snackbar != null) snackbar.dismiss();
        snackbar = Snackbar.make(findViewById(R.id.mainActivityLayout), "Adding geofence done", Snackbar.LENGTH_SHORT);
        snackbar.show();
    }

    @Override
    public void onItemClick(View v, int position) {
        //Triggers when recyclerview item clicked.
        if (snackbar != null) snackbar.dismiss();
        snackbar = Snackbar.make(findViewById(R.id.mainActivityLayout), "Item clicked!", Snackbar.LENGTH_SHORT);
        snackbar.show();

    }

    @Override
    public boolean onItemLongClick(View v, int position) {
        //Triggers when recyclerview item long clicked.
        if (snackbar != null) snackbar.dismiss();
        snackbar = Snackbar.make(findViewById(R.id.mainActivityLayout), "Long clicked on the item", Snackbar.LENGTH_SHORT);
        snackbar.show();

        return false;
    }

    @Override
    public void onItemSwipe(final int position) {
        if (geofencingClient == null) {
            geofencingClient = LocationServices.getGeofencingClient(this);
        }
        AutoSilenceLocation location = listAdapter.getItem(position);
        ArrayList<String> requestIdList = new ArrayList<>();
        requestIdList.add(location.getRequestId());
        try {
            geofencingClient.removeGeofences(requestIdList).addOnCompleteListener(this);
            if (locationDBHelper.remove(location.getId())) {
                if (snackbar != null) snackbar.dismiss();
                snackbar = Snackbar.make(findViewById(R.id.mainActivityLayout), "Item deleted!", Snackbar.LENGTH_SHORT);
                snackbar.show();
                listAdapter.removeLocation(position);
            }
        } catch (SecurityException e) {
            Toast.makeText(this, Log.getStackTraceString(e), Toast.LENGTH_LONG).show();
            if (snackbar != null) snackbar.dismiss();
            snackbar = Snackbar.make(findViewById(R.id.mainActivityLayout), "Can't delete!", Snackbar.LENGTH_SHORT);
            snackbar.show();
            listAdapter.notifyDataSetChanged();
        }
    }

    @SuppressWarnings("StaticFieldLeak")
    class LocationLoadTask extends AsyncTask<Void, Void, ArrayList<AutoSilenceLocation>> {
        @Override
        protected ArrayList<AutoSilenceLocation> doInBackground(Void... voids) {
            return locationDBHelper.getAllData();
        }

        @Override
        protected void onPostExecute(ArrayList<AutoSilenceLocation> autoSilenceLocations) {
            super.onPostExecute(autoSilenceLocations);
            listAdapter.setLocations(autoSilenceLocations);
        }
    }

    private static final int FOREGROUND_PERMISSIONS_REQUEST_CODE = 777;
    private static final int BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE = 778;

    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    || ActivityCompat.checkSelfPermission(this, Manifest.permission.MODIFY_AUDIO_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
                String[] permissions = {Manifest.permission.MODIFY_AUDIO_SETTINGS, Manifest.permission.ACCESS_FINE_LOCATION};
                ActivityCompat.requestPermissions(this, permissions, FOREGROUND_PERMISSIONS_REQUEST_CODE);
            } else {
                checkBackgroundLocationPermission();
            }
        }
    }

    private void checkBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Background Location Permission")
                        .setMessage("This app requires background location access to automatically silence your phone. Please grant this permission.")
                        .setPositiveButton("Grant", (dialog, which) -> ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE))
                        .setNegativeButton("Deny", null)
                        .show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == FOREGROUND_PERMISSIONS_REQUEST_CODE) {
            boolean fineLocationGranted = false;
            for (int i = 0; i < permissions.length; i++) {
                if (permissions[i].equals(Manifest.permission.ACCESS_FINE_LOCATION) && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    fineLocationGranted = true;
                    break;
                }
            }

            if (fineLocationGranted) {
                checkBackgroundLocationPermission();
            } else {
                Toast.makeText(this, "Fine location permission is required for this app to work.", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Background location permission granted.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Background location permission is required for the app to work automatically.", Toast.LENGTH_LONG).show();
            }
        }
    }
}
