package com.ssm.speacall;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;

import android.app.Activity;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.provider.ContactsContract;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

public class MainActivity extends Activity {
	// Android UI
	private Button btnStart = null;
	private TextView mTextView;
	private Button btnGetContract = null;
	Contact contact = new Contact();

	// Message input/output situation division
	public static final int MSG_FROM_CLIENT = 1;
	private static final int MSG_TO_CLIENT = 2;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		// this.startService(new Intent(this,SpeacallService.class));
		// finish();

		SocketHandler handler = new SocketHandler();

		// get Contact list
		ArrayList<Contact> arContactList = new ArrayList<Contact>();
		arContactList = getContactList();

		ArrayList<String> arGeneral = new ArrayList<String>();
		for(int i=0;i<arContactList.size();i++){
			//arGeneral.add(arContactList.get(i).name + arContactList.get(i).phonenum);
			arGeneral.add(arContactList.get(i).phonenum);
		}	
		ArrayAdapter<String> Adapter;
		Adapter = new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,arGeneral);		
		ListView list = (ListView) findViewById(R.id.list);
		list.setAdapter(Adapter);
		

		// Socket Server start on Background Thread
		final SocketServer server = new SocketServer(handler);
		Thread serverThread = new Thread(server);
		serverThread.start();
		mTextView = (TextView) findViewById(R.id.text_field);
		btnGetContract = (Button) findViewById(R.id.btnGetContract);
		btnStart = (Button) findViewById(R.id.btnStart);
		btnStart.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				// TODO Auto-generated method stub
				char mMessage = 'd';
				Message temp_msg = Message.obtain();
				temp_msg.what = MSG_TO_CLIENT;
				temp_msg.obj = mMessage;
				server.mMessageHandler.sendMessage(temp_msg);

			}
		});
		
		
		
		
	}



	// PC�뿉�꽌 蹂대궡�삩 硫붿떆吏�瑜� TextView�뿉 �몴�떆
	private class SocketHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			super.handleMessage(msg);
			switch (msg.what) {
			case MSG_FROM_CLIENT:
				String message = (String) msg.obj;
				mTextView.append("Message : " + message + "\n");
				Log.d("hak", "from pc  :  "+message);
				break;

			default:
				break;
			}
		}
	}

	// Android Socket Server
	private class SocketServer implements Runnable {

		// Port
		private static final int SERVER_PORT = 5037;

		// Input/output Stream
		private ServerSocket mSocketServer = null;
		BufferedWriter mServerWriter = null;
		BufferedReader mReaderFromClient = null;

		// message handler
		Handler mMainHandler;

		// PC message sending handler
		Handler mMessageHandler = new Handler() {
			public void handleMessage(Message msg) {
				switch (msg.what) {
				case MSG_TO_CLIENT:
					try {
						mServerWriter.write(msg.obj + "\n");
						
						mServerWriter.flush();
					} catch (IOException e) {
						e.printStackTrace();
						//
					}
					break;

				default:
					break;
				}
			};
		};

		// Socket server Object maker
		public SocketServer(Handler handler) {
			this.mMainHandler = handler;
		}

		@Override
		public void run() {

			try {
				// Socket Communication service start!!
				mSocketServer = new ServerSocket(SERVER_PORT);
				System.out.println("connecting...");
				Socket client = mSocketServer.accept();

				// Ready for Input/Output Stream
				InputStream is = client.getInputStream();
				OutputStream os = client.getOutputStream();

				// Connect Input/Output Stream
				mReaderFromClient = new BufferedReader(
						new InputStreamReader(is));
				mServerWriter = new BufferedWriter(new OutputStreamWriter(os));
			} catch (IOException e) {
				e.printStackTrace();
				// mEditText.setText(e.getCause().getLocalizedMessage());
			}

			try {
				// Receiving message from PC
				while (true) {
					String msg = "";
					msg = mReaderFromClient.readLine();

					if (msg.equals("exit")) {
						break;
					} else if (msg != null && msg != "") {
						Message message = Message.obtain(mMainHandler,
								MSG_FROM_CLIENT);
						message.obj = msg;
						mMainHandler.sendMessage(message);
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
				//
			}
			try {
				// Socket End
				mServerWriter.close();
				mReaderFromClient.close();
				mSocketServer.close();
			} catch (Exception e) {
				//
			}
		}
	}

	

	private ArrayList<Contact> getContactList() {
		Uri uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI;
		String[] projection = new String[] {
				ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
				ContactsContract.CommonDataKinds.Phone.NUMBER,
				ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME };

		String[] selectionArgs = null;
		String sortOrder = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
				+ " COLLATE LOCALIZED ASC";

		// Cursor contactCursor = managedQuery(uri, projection, null,
		// selectionArgs, sortOrder);
		Cursor contactCursor = getContentResolver().query(uri, projection,
				null, selectionArgs, sortOrder);
		ArrayList<Contact> contactlist = new ArrayList<Contact>();
		if (contactCursor.moveToFirst()) {
			do {
				String phonenumber = contactCursor.getString(1).replaceAll("-",
						"");
//				if (phonenumber.length() == 10) {
//					phonenumber = phonenumber.substring(0, 3) + "-"
//							+ phonenumber.substring(3, 6) + "-"
//							+ phonenumber.substring(6);
//				} else if (phonenumber.length() > 8) {
//					phonenumber = phonenumber.substring(0, 3) + "-"
//							+ phonenumber.substring(3, 7) + "-"
//							+ phonenumber.substring(7);
//				}
				Contact acontact = new Contact();
				acontact.setPhotoid(contactCursor.getLong(0));
				acontact.setPhonenum(phonenumber);
				acontact.setName(contactCursor.getString(2));
				contactlist.add(acontact);

			} while (contactCursor.moveToNext());
		}

		return contactlist;
	}

	
	
	// Sound

	@Override
	protected void onPause() {
		// TODO Auto-generated method stub
		super.onPause();
	}

	@Override
	protected void onDestroy() {
		// TODO Auto-generated method stub
		super.onDestroy();
	}

}
