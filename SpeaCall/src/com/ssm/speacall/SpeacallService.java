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
import java.nio.charset.Charset;
import java.sql.Date;
import java.text.SimpleDateFormat;
import java.util.ArrayList;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class SpeacallService extends Service {
	private static final String TAG = "calllog";

	//공개 포트
	private static final int SERVER_PORT = 9500;
	// Android UI
	private Button btnStart = null;
	private TextView mTextView;
	private Button btnGetContract = null;
	Contact contact = new Contact();
	AudioTrack at;
	String[][] contactArray = new String[20][3] ;



	int bufferSize = 1920;
	final int SAMPLE_RATE = 48000 ;
	// not working 88200, 64000, 
	// working 48000, 44100
	//byte[] buffer = new byte[bufferSize];


	MessageReceiveTask mReceiveTask;
	AudioSynthesisTask audioSynth;
	boolean keepGoing = false;
	//BufferedReader mReaderFromClient = null;

	// Message input/output situation division
	public static final int MSG_FROM_CLIENT = 1;
	private static final int MSG_TO_CLIENT = 2;

	public static final String MESSAGE_TYPE_INBOX = "1";
	public static final String MESSAGE_TYPE_SENT = "2";
	public static final String MESSAGE_TYPE_CONVERSATIONS = "3";
	public static final String MESSAGE_TYPE_NEW = "new";
	public static final int MESSAGE_READ = 2;
	public static final int MESSAGE_WRITE = 3;

	boolean checkMessage = true;
	final static private String[] Call_Projection = {CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER,CallLog.Calls.DATE,CallLog.Calls.DURATION};
	ContactTask contactTask;
	SocketHandler handler ;
	SocketServer server ;
	Thread serverThread ;
	AudioThread auThread;


	@Override
	public IBinder onBind(Intent intent) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void onCreate() {
		// TODO Auto-generated method stub
		super.onCreate();

		callLog();
		getContactList();

		// PC에서 수신된 메시지를 처리할 핸들러
		SocketHandler handler= new SocketHandler();

		// 백그라운드 스레드에서 Socket Server 구동	

		server = new SocketServer(handler);
		Thread serverThread = new Thread(server);
		serverThread.start();

		Toast toast = Toast.makeText(getApplicationContext(), "App Start", Toast.LENGTH_SHORT);
		toast.show();

		Log.d("hak","onCreate");

	}


	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		// TODO Auto-generated method stub
		Log.d("hak","onStartCommand");


		return super.onStartCommand(intent, flags, startId);

	}


	// 안드로이드용 소켓 서버
	public class SocketServer implements Runnable {


		// 입출력 스트림 정의
		public ServerSocket mSocketServer = null;
		BufferedWriter mServerWriter = null;
		BufferedReader mReaderFromClient = null;
		InputStream is;
		OutputStream os;
		byte[] buffer = new byte[bufferSize];

		// 읽은 메시지를 UI에 전달할 핸들러
		Handler mMainHandler;

		//PC로 메시지 전송을 담당하는 핸들러
		Handler mMessageHandler = new Handler(){
			public void handleMessage(Message msg) {
				switch (msg.what) {
				case MSG_TO_CLIENT:
					try {
						mServerWriter.write(msg.obj+"\n");
						mServerWriter.flush();
					} catch (IOException e) {
						e.printStackTrace();

					}					
					break;

				default:
					break;
				}
			};
		};

		//  socket server object maker
		public SocketServer(Handler handler) {			
			this.mMainHandler = handler;					
		}

		@Override
		public void run() {

			int bytes;
			try {
				// 소켓 통신 서비스 시작
				mSocketServer = new ServerSocket(SERVER_PORT);
				System.out.println("connecting...");
				Log.d("hak","Connectiong....");
				Socket client = mSocketServer.accept();

				//입 출력용 스트림 준비
				is = client.getInputStream();
				os = client.getOutputStream();

				// 입 출력용 스트림 연결
				mReaderFromClient = new BufferedReader(new InputStreamReader(is));
				mServerWriter = new BufferedWriter(new OutputStreamWriter(os));
				auThread = new AudioThread();
				Thread audioThread = new Thread(auThread);
				audioThread.start();
				Log.d("hak", "Start for loop buffer");


				//				mReceiveTask = new MessageReceiveTask();
				//				mReceiveTask.execute();

				Log.d("hak","메시지 수신부");
				try {

					// Receiving message handler from PC
					while (true) {
						//while (checkMessage) {
						String msg = "";					
						msg = mReaderFromClient.readLine();
						if (msg.equals("exit")) {
							//checkMessage=false;
							break;
						}else if(msg.equals("8")){
							Message message = Message.obtain(server.mMainHandler, MSG_FROM_CLIENT);
							message.obj = msg;
							Log.d("hak","receivedMessage : "+(String)message.obj);
							mMainHandler.sendMessage(message);	
							checkMessage = true;
						}
						else if(msg!=null&&msg!="") {
							//							Message message = Message.obtain(server.mMainHandler, MSG_FROM_CLIENT);
							//							message.obj = msg;
							//							Log.d("hak","receivedMessage : "+(String)message.obj);
							//server.mMainHandler.sendMessage(message);	
							//checkMessage = true;



							for (int bytesRead; (bytesRead = is.read(buffer, 0,
									buffer.length)) != -1;) {
								at.write(buffer, 0, buffer.length);					
							}
						}
					}


				} catch (Exception e) {
					e.printStackTrace();

				}


				//				audioSynth = new AudioSynthesisTask();
				//				audioSynth.execute();

				//				contactTask = new ContactTask();
				//				contactTask.execute();



				//				for (int bytesRead; (bytesRead = is.read(buffer, 0,
				//						buffer.length)) != -1;) {
				//					at.write(buffer, 0, buffer.length);					
				//				}
				Log.d("hak", "end buffer");


			} catch (IOException e) {
				e.printStackTrace();

			}

			try {
				// 소켓 종료
				mServerWriter.close();
				mReaderFromClient.close();
				mSocketServer.close();
			} catch (Exception e) {

			}
		}
	}

	// PC에서 보내온 메시지를 TextView에 표시
	private class SocketHandler extends Handler{
		@Override
		public void handleMessage(Message msg) {
			super.handleMessage(msg);
			switch (msg.what) {
			case MSG_FROM_CLIENT:
				String message = (String) msg.obj;
				//mTextView.append("Message : " + message + "\n");
				Log.d("hak","message from pc: "+ message);
				break;

			default:
				break;
			}
		}
	}

	private class AudioThread implements Runnable{
		public AudioThread() {
			// TODO Auto-generated constructor stub
		}

		public void run(){
			try {


				System.out.println("connecting...");

				int nByteRead =0;
				int overallBytes=0;


				Log.d("hak", "audio in Background");

				int minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE,
						AudioFormat.CHANNEL_CONFIGURATION_STEREO,
						AudioFormat.ENCODING_PCM_16BIT);
				Log.d("hak", "2");

				at = new AudioTrack(AudioManager.STREAM_MUSIC,
						SAMPLE_RATE, AudioFormat.CHANNEL_CONFIGURATION_STEREO,
						AudioFormat.ENCODING_PCM_16BIT, minBufferSize,
						AudioTrack.MODE_STREAM);
				Log.d("hak", "3");				

				keepGoing = true;	
				at.play();
				//				Log.d("hak", "here is not wkrking?");	
				//				while(server.is.read(server.buffer, 0,
				//						server.buffer.length) != -1) {
				//					at.write(server.buffer, 0, server.buffer.length);					
				//				}
				//
				//				Log.d("hak", "no");	

			} catch (Exception e) {
				e.printStackTrace();
				Log.d("hak", "audio thread is not working");
			}
		}
	}

	private class SendMessageTask extends AsyncTask<Void, Void, Void> {

		@Override
		protected Void doInBackground(Void... params) {
			try {

				Log.d(TAG,"send message Task : ");

			} catch (Exception e) {
				e.printStackTrace();
				Log.d("hak", "SendMessageTask task is not working");
			}
			return null;
		}

	}



	private class ContactTask extends AsyncTask<Void, Void, Void> {

		@Override
		protected Void doInBackground(Void... params) {
			try {
				// get Contact list

				ArrayList<Contact> arContactList = new ArrayList<Contact>();
				arContactList = getContactList();
				String mMessage=null;
				ArrayList<String> arGeneral = new ArrayList<String>();

				for (int i = 0; i < arContactList.size(); i++) {

					arGeneral.add(arContactList.get(i).phonenum);
					if(arContactList.get(i).phonenum.equals(arContactList.get(i+1).phonenum)&&arContactList.get(i).phonenum.equals(arContactList.get(i+2).phonenum)  && i<arContactList.size()-2) 
					{
						mMessage = null;
						continue;
					}
					Message temp_msg = Message.obtain();
					mMessage = arContactList.get(i).getName().toString() 
							+"//"+arContactList.get(i).phonenum.toString()+"//"+arContactList.get(i).photoid;

					temp_msg.what = MSG_TO_CLIENT;
					temp_msg.obj = mMessage;

					server.mMessageHandler.sendMessage(temp_msg);

					Log.d("hak","contract : "+ mMessage);
					mMessage = null;
				}
				Message temp_msg = Message.obtain();
				temp_msg.what = MSG_TO_CLIENT;
				temp_msg.obj = "end";
				server.mMessageHandler.sendMessage(temp_msg);


				Log.d(TAG,"send333 : ");

			} catch (Exception e) {
				e.printStackTrace();
				Log.d("hak", "contact task is not working");
			}
			return null;
		}

	}


	private class AudioSynthesisTask extends AsyncTask<Void, Void, Void> {
		//byte[] buffer = new byte[bufferSize];
		@Override
		protected Void doInBackground(Void... params) {
			try {

				System.out.println("connecting...");

				int nByteRead =0;
				int overallBytes=0;

				Log.d("hak", "audio in Background");

				int minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE,
						AudioFormat.CHANNEL_CONFIGURATION_STEREO,
						AudioFormat.ENCODING_PCM_16BIT);
				Log.d("hak", "2");

				at = new AudioTrack(AudioManager.STREAM_MUSIC,
						SAMPLE_RATE, AudioFormat.CHANNEL_CONFIGURATION_STEREO,
						AudioFormat.ENCODING_PCM_16BIT, minBufferSize,
						AudioTrack.MODE_STREAM);
				Log.d("hak", "3");				
				//server.is.read(server.buffer,0,server.buffer.length);
				keepGoing = true;	
				at.play();
				Log.d("hak", "here is not wkrking?");	
				for (int bytesRead; (bytesRead = server.is.read(server.buffer, 0,
						server.buffer.length)) != -1;) {
					at.write(server.buffer, 0, server.buffer.length);					
				}
				//				for (int bytesRead; (bytesRead = server.is.read(buffer, 0,
				//						buffer.length)) != -1;) {
				//at.write(buffer, 0, buffer.length);					
				//				}
				Log.d("hak", "no");	

				/*
				while(keepGoing){

					try{
						nByteRead = server.is.read(buffer);
					}
					catch(IOException e){
						Log.e(TAG,"IOException");
					}

					if(nByteRead >= 0){
at.write(buffer, 0, buffer.length);	

					}


				}
				do{

				}while(nByteRead>0);
				 */
			} catch (Exception e) {
				e.printStackTrace();
				Log.d("hak", "audio task is not working");
			}
			return null;
		}

	}




	private class MessageReceiveTask extends AsyncTask<Void, Void, Void> {


		@Override
		protected Void doInBackground(Void... params) {
			try {

				Log.d("hak","메시지 수신부");
				try {

					// Receiving message handler from PC
					while (true) {
						Log.d("hak","1");
						//while (checkMessage) {
						String msg = "";					
						msg = server.mReaderFromClient.readLine();
						Log.d("hak","2");
						if (msg.equals("exit")) {
							//checkMessage=false;
							break;
						} else if(msg!=null&&msg!="") {
							Log.d("hak","3");
							Message message = Message.obtain(server.mMainHandler, MSG_FROM_CLIENT);
							message.obj = msg;
							Log.d("hak","receivedMessage : "+(String)message.obj);
							server.mMainHandler.sendMessage(message);	
							//checkMessage = true;
						}
					}
					Log.d("hak","메시지 수신부 End!!!");


				} catch (Exception e) {
					e.printStackTrace();

				}

			} catch (Exception e) {
				e.printStackTrace();
				Log.d("hak", "message task is not working");
			}
			return null;
		}

	}

	// Contact 

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
				Contact acontact = new Contact();
				acontact.setPhotoid(contactCursor.getLong(0));
				acontact.setPhonenum(phonenumber);
				acontact.setName(contactCursor.getString(2));
				contactlist.add(acontact);
				//Log.d("hak","contact list : "+contactCursor.getString(2) + phonenumber);
			} while (contactCursor.moveToNext());
		}

		return contactlist;
	}




	// Get Call Log

	private Cursor getCallHistoryCursor(Context context){
		Cursor cursor = context.getContentResolver().query(CallLog.Calls.CONTENT_URI, Call_Projection, null, null, CallLog.Calls.DEFAULT_SORT_ORDER);
		return cursor;
	}

	private void callLog() {
		int callcount = 0;
		String callname = "";
		String calltype = "";
		String calllog = "";
		Cursor curCallLog = getCallHistoryCursor(this);
		Log.i( TAG , "processSend() - 1");
		// Log.i( TAG , "curCallLog: " + curCallLog.getCount());
		if (curCallLog.moveToFirst() && curCallLog.getCount() > 0) {
			while (curCallLog.isAfterLast() == false) {
				StringBuffer sb = new StringBuffer();

				if (curCallLog.getString(curCallLog
						.getColumnIndex(CallLog.Calls.TYPE)).equals(MESSAGE_TYPE_INBOX)){
					calltype = "수신";
				}
				else if (curCallLog.getString(curCallLog
						.getColumnIndex(CallLog.Calls.TYPE)).equals(MESSAGE_TYPE_SENT)){
					calltype = "발신";                   
				}
				else if (curCallLog.getString(curCallLog
						.getColumnIndex(CallLog.Calls.TYPE)).equals(MESSAGE_TYPE_CONVERSATIONS)){
					calltype = "부재중";                   
				}

				if (curCallLog.getString(curCallLog
						.getColumnIndex(CallLog.Calls.CACHED_NAME)) == null) {
					callname = "NoName";
				}
				else {
					callname = curCallLog.getString(curCallLog
							.getColumnIndex(CallLog.Calls.CACHED_NAME));
				}
				sb.append(timeToString(curCallLog.getLong(curCallLog
						.getColumnIndex(CallLog.Calls.DATE))));
				sb.append("\t").append(calltype);
				sb.append("\t").append(callname);
				sb.append("\t").append(curCallLog.getString(curCallLog.getColumnIndex(CallLog.Calls.NUMBER)));
				curCallLog.moveToNext();

				String backupData = sb.toString();

				callcount++;
				//Log.d("calllog", sb.toString());
			}
		}
	}

	private String timeToString(Long time) {
		SimpleDateFormat simpleFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		String date = simpleFormat.format(new Date(time));
		return date;
	}   


	@Override
	public void onStart(Intent intent, int startId) {
		// TODO Auto-generated method stub
		super.onStart(intent, startId);
		Log.d("hak","onStart");

	}


	@Override
	public void onDestroy() {
		// TODO Auto-generated method stub
		super.onDestroy();
		Log.d("hak","Destroy");
	}

}
