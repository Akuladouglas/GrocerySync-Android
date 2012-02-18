package com.couchbase.grocerysync;

import java.io.IOException;
import java.util.Map;

import org.codehaus.jackson.JsonNode;
import org.ektorp.CouchDbConnector;
import org.ektorp.CouchDbInstance;
import org.ektorp.ReplicationCommand;
import org.ektorp.UpdateConflictException;
import org.ektorp.ViewQuery;
import org.ektorp.ViewResult.Row;
import org.ektorp.http.HttpClient;
import org.ektorp.impl.StdCouchDbInstance;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnKeyListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.EditText;
import android.widget.ListView;

import com.couchbase.touchdb.TDDatabase;
import com.couchbase.touchdb.TDServer;
import com.couchbase.touchdb.TDView;
import com.couchbase.touchdb.TDViewMapBlock;
import com.couchbase.touchdb.TDViewMapEmitBlock;
import com.couchbase.touchdb.ektorp.TouchDBHttpClient;
import com.couchbase.touchdb.router.TDURLStreamHandlerFactory;

public class AndroidGrocerySyncActivity extends Activity implements OnItemClickListener, OnItemLongClickListener, OnKeyListener {

	public static String TAG = "GrocerySync";

	//constants
	public static final String DATABASE_NAME = "grocery-sync";
	public static final String dDocName = "grocery-local";
	public static final String dDocId = "_design/" + dDocName;
	public static final String byDateViewName = "byDate";

	//splash screen
	protected SplashScreenDialog splashDialog;

	//main screen
	protected EditText addItemEditText;
	protected ListView itemListView;
	protected GrocerySyncListAdapter itemListViewAdapter;

	//couch internals
	protected static TDServer server;
	protected static HttpClient httpClient;

	//ektorp impl
	protected CouchDbInstance dbInstance;
	protected CouchDbConnector couchDbConnector;
	protected ReplicationCommand pushReplicationCommand;
	protected ReplicationCommand pullReplicationCommand;

    //static inializer to ensure that touchdb:// URLs are handled properly
    {
        TDURLStreamHandlerFactory.registerSelfIgnoreError();
    }

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.main);

        //connect items from layout
        addItemEditText = (EditText)findViewById(R.id.addItemEditText);
        itemListView = (ListView)findViewById(R.id.itemListView);

        //connect listeners
		addItemEditText.setOnKeyListener(this);

		//show splash and start couch
		showSplashScreen();
		removeSplashScreen();
		startTouchDB();
        startEktorp();
    }

	protected void onDestroy() {
		Log.v(TAG, "onDestroy");

		//need to stop the async task thats following the changes feed
		itemListViewAdapter.cancelContinuous();

		//clean up our http client connection manager
		if(httpClient != null) {
			httpClient.shutdown();
		}

		if(server != null) {
		    server.close();
		}

		super.onDestroy();
	}

	protected void startTouchDB() {
	    String filesDir = getFilesDir().getAbsolutePath();
	    try {
            server = new TDServer(filesDir);
        } catch (IOException e) {
            Log.e(TAG, "Error starting TDServer", e);
        }

	    //install a view definition needed by the application
	    TDDatabase db = server.getDatabaseNamed(DATABASE_NAME);
	    TDView view = db.getViewNamed(String.format("%s/%s", dDocName, byDateViewName));
	    view.setMapReduceBlocks(new TDViewMapBlock() {

            @Override
            public void map(Map<String, Object> document, TDViewMapEmitBlock emitter) {
                String createdAt = document.get("created_at").toString();
                if(createdAt != null) {
                    emitter.emit(createdAt, document);
                }
            }
        }, null, "1.0");
	}

	protected void startEktorp() {
		Log.v(TAG, "starting ektorp");

		if(httpClient != null) {
			httpClient.shutdown();
		}

		httpClient = new TouchDBHttpClient(server);
		dbInstance = new StdCouchDbInstance(httpClient);

		GrocerySyncEktorpAsyncTask startupTask = new GrocerySyncEktorpAsyncTask() {

			@Override
			protected void doInBackground() {
				couchDbConnector = dbInstance.createConnector(DATABASE_NAME, true);
			}

			@Override
			protected void onSuccess() {
				//attach list adapter to the list and handle clicks
				ViewQuery viewQuery = new ViewQuery().designDocId(dDocId).viewName(byDateViewName).descending(true);
				itemListViewAdapter = new GrocerySyncListAdapter(AndroidGrocerySyncActivity.this, couchDbConnector, viewQuery);
				itemListView.setAdapter(itemListViewAdapter);
				itemListView.setOnItemClickListener(AndroidGrocerySyncActivity.this);
				itemListView.setOnItemLongClickListener(AndroidGrocerySyncActivity.this);

				startReplications();
			}
		};
		startupTask.execute();
	}

	public void startReplications() {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());

		pushReplicationCommand = new ReplicationCommand.Builder()
			.source(DATABASE_NAME)
			.target(prefs.getString("sync_url", "http://mschoch.iriscouch.com/grocery-test"))
			.continuous(true)
			.build();

		GrocerySyncEktorpAsyncTask pushReplication = new GrocerySyncEktorpAsyncTask() {

			@Override
			protected void doInBackground() {
				dbInstance.replicate(pushReplicationCommand);
			}
		};

		pushReplication.execute();

		pullReplicationCommand = new ReplicationCommand.Builder()
			.source(prefs.getString("sync_url", "http://mschoch.iriscouch.com/grocery-test"))
			.target(DATABASE_NAME)
			.continuous(true)
			.build();

		GrocerySyncEktorpAsyncTask pullReplication = new GrocerySyncEktorpAsyncTask() {

			@Override
			protected void doInBackground() {
				dbInstance.replicate(pullReplicationCommand);
			}
		};

		pullReplication.execute();
	}

	public void stopEktorp() {
	}


	/**
	 * Handle typing item text
	 */
	public boolean onKey(View v, int keyCode, KeyEvent event) {
		if ((event.getAction() == KeyEvent.ACTION_DOWN)
				&& (keyCode == KeyEvent.KEYCODE_ENTER)) {

			String inputText = addItemEditText.getText().toString();
			if(!inputText.equals("")) {
				createGroceryItem(inputText);
			}
			addItemEditText.setText("");
			return true;
		}
		return false;
	}

	/**
	 * Handle click on item in list
	 */
	public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        Row row = (Row)parent.getItemAtPosition(position);
        JsonNode item = row.getValueAsNode();
		toggleItemChecked(item);
	}

	/**
	 * Handle long-click on item in list
	 */
	public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        Row row = (Row)parent.getItemAtPosition(position);
        final JsonNode item = row.getValueAsNode();
		JsonNode textNode = item.get("text");
		String itemText = "";
		if(textNode != null) {
			itemText = textNode.getTextValue();
		}

		AlertDialog.Builder builder = new AlertDialog.Builder(AndroidGrocerySyncActivity.this);
		AlertDialog alert = builder.setTitle("Delete Item?")
			   .setMessage("Are you sure you want to delete \"" + itemText + "\"?")
		       .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
		           public void onClick(DialogInterface dialog, int id) {
		        	   deleteGroceryItem(item);
		           }
		       })
		       .setNegativeButton("No", new DialogInterface.OnClickListener() {
		           public void onClick(DialogInterface dialog, int id) {
		               // Handle Cancel
		           }
		       })
		       .create();

		alert.show();

		return true;
	}

	/**
	 * Removes the Dialog that displays the splash screen
	 */
	protected void removeSplashScreen() {
	    if (splashDialog != null) {
	        splashDialog.dismiss();
	        splashDialog = null;
	    }
	}

	/**
	 * Shows the splash screen over the full Activity
	 */
	protected void showSplashScreen() {
	    splashDialog = new SplashScreenDialog(this);
	    splashDialog.show();
	}

	/**
	 * Add settings item to the menu
	 */
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, 0, 0, "Settings");
        return super.onCreateOptionsMenu(menu);
    }

    /**
     * Launch the settings activity
     */
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 0:
                startActivity(new Intent(this, GrocerySyncPreferencesActivity.class));
                return true;
        }
        return false;
    }

    public void createGroceryItem(String name) {
        final JsonNode item = GroceryItemUtils.createWithText(name);
        GrocerySyncEktorpAsyncTask createItemTask = new GrocerySyncEktorpAsyncTask() {

			@Override
			protected void doInBackground() {
				couchDbConnector.create(item);
			}

			@Override
			protected void onSuccess() {
				Log.d(TAG, "Document created successfully");
			}

			@Override
			protected void onUpdateConflict(
					UpdateConflictException updateConflictException) {
				Log.d(TAG, "Got an update conflict for: " + item.toString());
			}
		};
	    createItemTask.execute();
    }

    public void toggleItemChecked(final JsonNode item) {
        GroceryItemUtils.toggleCheck(item);

        GrocerySyncEktorpAsyncTask updateTask = new GrocerySyncEktorpAsyncTask() {

			@Override
			protected void doInBackground() {
				couchDbConnector.update(item);
			}

			@Override
			protected void onSuccess() {
				Log.d(TAG, "Document updated successfully");
			}

			@Override
			protected void onUpdateConflict(
					UpdateConflictException updateConflictException) {
				Log.d(TAG, "Got an update conflict for: " + item.toString());
			}
		};
	    updateTask.execute();
    }

    public void deleteGroceryItem(final JsonNode item) {
        GrocerySyncEktorpAsyncTask deleteTask = new GrocerySyncEktorpAsyncTask() {

			@Override
			protected void doInBackground() {
				couchDbConnector.delete(item);
			}

			@Override
			protected void onSuccess() {
				Log.d(TAG, "Document deleted successfully");
			}

			@Override
			protected void onUpdateConflict(
					UpdateConflictException updateConflictException) {
				Log.d(TAG, "Got an update conflict for: " + item.toString());
			}
		};
	    deleteTask.execute();
    }

}