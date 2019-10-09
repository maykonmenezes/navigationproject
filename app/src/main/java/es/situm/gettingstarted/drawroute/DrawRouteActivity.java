package es.situm.gettingstarted.drawroute;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import androidx.annotation.Nullable;
import com.google.android.material.snackbar.Snackbar;

import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.GroundOverlayOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import es.situm.gettingstarted.R;
import es.situm.gettingstarted.common.SampleActivity;
import es.situm.gettingstarted.drawpois.GetPoiCategoryIconUseCase;
import es.situm.gettingstarted.drawpois.GetPoisUseCase;
import es.situm.sdk.SitumSdk;
import es.situm.sdk.directions.DirectionsRequest;
import es.situm.sdk.error.Error;
import es.situm.sdk.location.util.CoordinateConverter;
import es.situm.sdk.model.cartography.Building;
import es.situm.sdk.model.cartography.Floor;
import es.situm.sdk.model.cartography.Poi;
import es.situm.sdk.model.cartography.Point;
import es.situm.sdk.model.directions.Route;
import es.situm.sdk.model.directions.RouteSegment;
import es.situm.sdk.model.location.Bounds;
import es.situm.sdk.model.location.CartesianCoordinate;
import es.situm.sdk.model.location.Coordinate;
import es.situm.sdk.utils.Handler;
import es.situm.gettingstarted.drawpois.DrawPoisActivity;


public class DrawRouteActivity
        extends SampleActivity
        implements OnMapReadyCallback {

    private final String TAG = getClass().getSimpleName();
    private GetPoisUseCase getPoisUseCase = new GetPoisUseCase();
    private GetPoiCategoryIconUseCase getPoiCategoryIconUseCase = new GetPoiCategoryIconUseCase();
    private ProgressBar progressBar;
    private GoogleMap googleMap;
    private Building building;
    private List<Polyline> polylines = new ArrayList<>();
    private Marker markerDestination,markerOrigin;
    private String floorId;
    private Point pointOrigin;
    private CoordinateConverter coordinateConverter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_draw_route);
        getSupportActionBar().setSubtitle(R.string.tv_select_points);
        building = getBuildingFromIntent();
        setup();
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);
    }

    @Override
    protected void onDestroy() {
        getPoisUseCase.cancel();
        SitumSdk.navigationManager().removeUpdates();
        super.onDestroy();
    }

    @Override
    public void onMapReady(final GoogleMap googleMap) {
        this.googleMap = googleMap;
        fetchFirstFloorImage(building, new Callback() {
            @Override
            public void onSuccess(Bitmap floorImage) {
                coordinateConverter = new CoordinateConverter(building.getDimensions(),building.getCenter(),building.getRotation());
                drawBuilding(floorImage);
            }

            @Override
            public void onError(Error error) {
                Snackbar.make(findViewById(R.id.container), error.getMessage(), Snackbar.LENGTH_INDEFINITE).show();
            }
        });

        this.googleMap.setOnMapClickListener(latLng -> {
            if (pointOrigin == null) {
                if(markerOrigin!=null){
                    clearMap();
                }
                markerOrigin = googleMap.addMarker(new MarkerOptions().position(latLng).title("origin"));
                pointOrigin = createPoint(latLng);
            }else {
                markerDestination = googleMap.addMarker(new MarkerOptions().position(latLng).title("destination"));
                calculateRoute(latLng);
            }


        });

        /*getPois(googleMap);*/
    }

    private void getPois(final GoogleMap googleMap){
        getPoisUseCase.get(new GetPoisUseCase.Callback() {
            @Override
            public void onSuccess(Building building, Collection<Poi> pois) {
                hideProgress();
                if (pois.isEmpty()){
                    Toast.makeText(DrawRouteActivity.this, "There isnt any poi in the building: " + building.getName() + ". Go to the situm dashboard and create at least one poi before execute again this example", Toast.LENGTH_LONG).show();
                }else {
                    for (final Poi poi : pois) {
                        getPoiCategoryIconUseCase.getUnselectedIcon(poi, new GetPoiCategoryIconUseCase.Callback() {
                            @Override
                            public void onSuccess(Bitmap bitmap) {
                                drawPoi(poi, bitmap);
                            }

                            @Override
                            public void onError(String error) {
                                Log.d("Error fetching poi icon", error);
                                drawPoi(poi);
                            }
                        });
                    }

                }
            }

            private void drawPoi(Poi poi, Bitmap bitmap) {
                LatLngBounds.Builder builder = new LatLngBounds.Builder();
                LatLng latLng = new LatLng(poi.getCoordinate().getLatitude(),
                        poi.getCoordinate().getLongitude());
                MarkerOptions markerOptions = new MarkerOptions()
                        .position(latLng)
                        .title(poi.getName());
                if (bitmap != null) {
                    markerOptions.icon(BitmapDescriptorFactory.fromBitmap(bitmap));
                }
                googleMap.addMarker(markerOptions);
                builder.include(latLng);
                googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 100));
            }

            private void drawPoi(Poi poi) {
                drawPoi(poi, null);
            }

            @Override
            public void onError(String error) {
                hideProgress();
                Toast.makeText(DrawRouteActivity.this, error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void calculateRoute(LatLng latLng) {
        Point pointDestination = createPoint(latLng);
        DirectionsRequest directionsRequest = new DirectionsRequest.Builder()
                .from(pointOrigin, null)
                .to(pointDestination)
                .build();
        SitumSdk.directionsManager().requestDirections(directionsRequest, new Handler<Route>() {
            @Override
            public void onSuccess(Route route) {
                drawRoute(route);
                centerCamera(route);
                hideProgress();
                pointOrigin = null;

            }

            @Override
            public void onFailure(Error error) {
                hideProgress();
                Toast.makeText(DrawRouteActivity.this, error.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void clearMap(){
        markerOrigin.remove();
        markerDestination.remove();
        removePolylines();
    }
    private Point createPoint(LatLng latLng) {
        Coordinate coordinate = new Coordinate(latLng.latitude, latLng.longitude);
        CartesianCoordinate cartesianCoordinate= coordinateConverter.toCartesianCoordinate(coordinate);
        Point point = new Point(building.getIdentifier(), floorId,coordinate,cartesianCoordinate );
        return point;
    }

    private void removePolylines() {
        for (Polyline polyline : polylines) {
            polyline.remove();
        }
        polylines.clear();
    }

    private void drawRoute(Route route) {
        for (RouteSegment segment : route.getSegments()) {
            //For each segment you must draw a polyline
            //Add an if to filter and draw only the current selected floor
            List<LatLng> latLngs = new ArrayList<>();
            for (Point point : segment.getPoints()) {
                latLngs.add(new LatLng(point.getCoordinate().getLatitude(), point.getCoordinate().getLongitude()));
            }

            PolylineOptions polyLineOptions = new PolylineOptions()
                    .color(Color.GREEN)
                    .width(4f)
                    .addAll(latLngs);
            polylines.add(googleMap.addPolyline(polyLineOptions));

        }
    }
    void drawBuilding(Bitmap bitmap) {
        Bounds drawBounds = building.getBounds();
        Coordinate coordinateNE = drawBounds.getNorthEast();
        Coordinate coordinateSW = drawBounds.getSouthWest();
        LatLngBounds latLngBounds = new LatLngBounds(
                new LatLng(coordinateSW.getLatitude(), coordinateSW.getLongitude()),
                new LatLng(coordinateNE.getLatitude(), coordinateNE.getLongitude()));

        this.googleMap.addGroundOverlay(new GroundOverlayOptions()
                .image(BitmapDescriptorFactory.fromBitmap(bitmap))
                .bearing((float) building.getRotation().degrees())
                .positionFromBounds(latLngBounds));

        this.googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(latLngBounds, 100));
    }

    private void centerCamera(Route route) {
        Coordinate from = route.getFrom().getCoordinate();
        Coordinate to = route.getTo().getCoordinate();

        LatLngBounds.Builder builder = new LatLngBounds.Builder()
                .include(new LatLng(from.getLatitude(), from.getLongitude()))
                .include(new LatLng(to.getLatitude(), to.getLongitude()));
        googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 100));
    }

    private void setup() {
        progressBar = (ProgressBar) findViewById(R.id.progressBar);
    }

    private void hideProgress(){
        progressBar.setVisibility(View.GONE);
    }
    void fetchFirstFloorImage(Building building,Callback callback) {
        SitumSdk.communicationManager().fetchFloorsFromBuilding(building, new Handler<Collection<Floor>>() {
            @Override
            public void onSuccess(Collection<Floor> floorsCollection) {
                List<Floor> floors = new ArrayList<>(floorsCollection);
                Floor firstFloor = floors.get(0);
                floorId =firstFloor.getIdentifier();
                SitumSdk.communicationManager().fetchMapFromFloor(firstFloor, new Handler<Bitmap>() {
                    @Override
                    public void onSuccess(Bitmap bitmap) {
                        callback.onSuccess(bitmap);
                    }

                    @Override
                    public void onFailure(Error error) {
                        callback.onError(error);
                    }
                });
            }

            @Override
            public void onFailure(Error error) {
                callback.onError(error);
            }
        });
    }

    interface Callback {
        void onSuccess(Bitmap floorImage);

        void onError(Error error);
    }
}
