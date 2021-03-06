package de.terabaud.sunshine;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.DataSetObserver;
import android.net.Uri;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.os.Bundle;
import android.text.format.Time;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Adapter;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.Map;

/**
 * A placeholder fragment containing a simple view.
 */
public class ForecastFragment extends Fragment {

    ArrayAdapter<String> mForecastAdapter;
    List<String> mForecastData;

    public ForecastFragment() {
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.forecastfragment, menu);
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_refresh) {
            FetchWeatherTask task = new FetchWeatherTask();
            task.execute();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View rootView =  inflater.inflate(R.layout.fragment_main, container, false);
        // url: http://api.openweathermap.org/data/2.5/forecast/daily?q=94043&mode=json&units=metric&cnt=7
        // gist: https://gist.github.com/udacityandroid/d6a7bb21904046a91695

        mForecastData = new ArrayList<String>();

        mForecastAdapter = new ArrayAdapter<String>((Context)getActivity(),
                R.layout.listitem_forecast, R.id.list_item_forecast_textview, mForecastData);
        ListView listView = (ListView) rootView.findViewById(R.id.forecast_list_view);
        listView.setAdapter(mForecastAdapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                String forecast = mForecastAdapter.getItem(position);
                // Toast.makeText(getActivity(), forecast, Toast.LENGTH_SHORT).show();
                Intent detailIntent = new Intent(getActivity(), DetailActivity.class)
                        .putExtra(Intent.EXTRA_TEXT, forecast);

                //detailIntent.setData()
                startActivity(detailIntent);
            }
        });

        FetchWeatherTask task = new FetchWeatherTask();
        task.execute();
        return rootView;
    }

    public class FetchWeatherTask extends AsyncTask<String, Integer, String[]> {

        private String getLocation() {
            Context ctx = getActivity();
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
            return prefs.getString(getString(R.string.location_key), "26871,de");
        }

        /* The date/time conversion code is going to be moved outside the asynctask later,
         * so for convenience we're breaking it out into its own method now.
         */
        private String getReadableDateString(long time){
            // Because the API returns a unix timestamp (measured in seconds),
            // it must be converted to milliseconds in order to be converted to valid date.
            SimpleDateFormat shortenedDateFormat = new SimpleDateFormat("EEE MMM dd");
            return shortenedDateFormat.format(time);
        }

        /**
         * Prepare the weather high/lows for presentation.
         */
        private String formatHighLows(double high, double low, String unitType) {
            if (unitType.equals(getString(R.string.pref_temperature_units_imperial))) {
                high = (high * 1.8) + 32;
                low  = (low  * 1.8) + 32;
            } else if (!unitType.equals(getString(R.string.pref_temperature_units_metric))) {
                Log.d("Log", "unknown unit type");
            }

            // For presentation, assume the user doesn't care about tenths of a degree.
            long roundedHigh = Math.round(high);
            long roundedLow = Math.round(low);


            String highLowStr = roundedHigh + "/" + roundedLow;
            return highLowStr;
        }

        /**
         * Take the String representing the complete forecast in JSON Format and
         * pull out the data we need to construct the Strings needed for the wireframes.
         *
         * Fortunately parsing is easy:  constructor takes the JSON string and converts it
         * into an Object hierarchy for us.
         */
        private String[] getWeatherDataFromJson(String forecastJsonStr, int numDays)
                throws JSONException {

            // These are the names of the JSON objects that need to be extracted.
            final String OWM_LIST = "list";
            final String OWM_WEATHER = "weather";
            final String OWM_TEMPERATURE = "temp";
            final String OWM_MAX = "max";
            final String OWM_MIN = "min";
            final String OWM_DESCRIPTION = "main";

            JSONObject forecastJson = new JSONObject(forecastJsonStr);
            JSONArray weatherArray = forecastJson.getJSONArray(OWM_LIST);

            // OWM returns daily forecasts based upon the local time of the city that is being
            // asked for, which means that we need to know the GMT offset to translate this data
            // properly.

            // Since this data is also sent in-order and the first day is always the
            // current day, we're going to take advantage of that to get a nice
            // normalized UTC date for all of our weather.

            Time dayTime = new Time();
            dayTime.setToNow();

            // we start at the day returned by local time. Otherwise this is a mess.
            int julianStartDay = Time.getJulianDay(System.currentTimeMillis(), dayTime.gmtoff);

            // now we work exclusively in UTC
            dayTime = new Time();

            String[] resultStrs = new String[numDays];
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
            String unitType = prefs.getString(getString(R.string.pref_temperature_units_key),
                    getString(R.string.pref_temperature_units_metric));

            for(int i = 0; i < weatherArray.length(); i++) {
                // For now, using the format "Day, description, hi/low"
                String day;
                String description;
                String highAndLow;

                // Get the JSON object representing the day
                JSONObject dayForecast = weatherArray.getJSONObject(i);

                // The date/time is returned as a long.  We need to convert that
                // into something human-readable, since most people won't read "1400356800" as
                // "this saturday".
                long dateTime;
                // Cheating to convert this to UTC time, which is what we want anyhow
                dateTime = dayTime.setJulianDay(julianStartDay+i);
                day = getReadableDateString(dateTime);

                // description is in a child array called "weather", which is 1 element long.
                JSONObject weatherObject = dayForecast.getJSONArray(OWM_WEATHER).getJSONObject(0);
                description = weatherObject.getString(OWM_DESCRIPTION);

                // Temperatures are in a child object called "temp".  Try not to name variables
                // "temp" when working with temperature.  It confuses everybody.
                JSONObject temperatureObject = dayForecast.getJSONObject(OWM_TEMPERATURE);
                double high = temperatureObject.getDouble(OWM_MAX);
                double low = temperatureObject.getDouble(OWM_MIN);

                highAndLow = formatHighLows(high, low, unitType);
                resultStrs[i] = day + " - " + description + " - " + highAndLow;
            }

            return resultStrs;

        }

        @Override
        protected String[] doInBackground(String... params) {
            String location = getLocation();
            String apiKey = "e26cb73612d30fb64f84c4d22d42f241";
            Uri u = Uri.parse("http://api.openweathermap.org/data/2.5/forecast/daily?").buildUpon()
                    .appendQueryParameter("q", location)
                    .appendQueryParameter("mode", "json")
                    .appendQueryParameter("units", "metric")
                    .appendQueryParameter("cnt", "7")
                    .appendQueryParameter("appid", apiKey).build();
            return makeRequest(u);
        }

        private String[] makeRequest(Uri address) {
            HttpURLConnection conn = null;
            BufferedReader br = null;
            try {
                URL url = new URL(address.toString());
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");

                InputStream is = conn.getInputStream();
                StringBuilder buf = new StringBuilder();
                if (is == null) return null;
                br = new BufferedReader(new InputStreamReader(is));

                String line;
                while ((line = br.readLine()) != null) {
                    buf.append(line);
                }
                int statusCode = conn.getResponseCode();
                String content = buf.length() == 0 ? null : buf.toString();
                if (statusCode != 200) return null;
                return getWeatherDataFromJson(content, 7);
            } catch (JSONException e) {
                Log.e("PlaceHolderFragment", "Error", e);
                return null;
            } catch (IOException e) {
                Log.e("PlaceHolderFragment", "Error", e);
                return null;
            } finally {
                if (conn != null) conn.disconnect();
                if (br != null) {
                    try {
                        br.close();
                    } catch (final IOException e) {
                        Log.e("PlaceHolderFragment", "Error closing stream", e);
                    }
                }
            }
        }


        @Override
        protected void onPostExecute(String[] result) {
            if (result == null) return;
            mForecastAdapter.clear();
            mForecastAdapter.addAll(result);
        }
    }



}
