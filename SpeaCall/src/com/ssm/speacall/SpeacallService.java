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
import java.util.logging.SocketHandler;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;

public class SpeacallService extends Service {
	
	// 메시지 송/수신 상황 구분 
	public static final int MSG_FROM_CLIENT = 1;
	private static final int MSG_TO_CLIENT = 2;
	
	

	@Override
	public IBinder onBind(Intent intent) {
		// TODO Auto-generated method stub
		return null;
	}
	
	@SuppressWarnings("deprecation")
	@Override
	public void onStart(Intent intent, int startId) {
		// TODO Auto-generated method stub
		super.onStart(intent, startId);
		
		// PC에서 수신된 메시지를 처리할 핸들러
		SocketHandler handler= new SocketHandler();
		
		// 백그라운드 스레드에서 Socket Server 구동
		final SocketServer server = new SocketServer(handler);
		Thread serverThread = new Thread(server);
		serverThread.start();
	}
	
	
	@Override
	public void onDestroy() {
		// TODO Auto-generated method stub
		super.onDestroy();
	}
	
	// 안드로이드용 소켓 서버
		private class SocketServer implements Runnable {

			//공개 포트
			private static final int SERVER_PORT = 9500;

			// 입출력 스트림 정의
			private ServerSocket mSocketServer = null;
			BufferedWriter mServerWriter = null;
			BufferedReader mReaderFromClient = null;
			
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

			// 소켓 서버 객체 생성자
			public SocketServer(Handler handler) {			
				this.mMainHandler = handler;					
			}

			@Override
			public void run() {
				
				try {
					// 소켓 통신 서비스 시작
					mSocketServer = new ServerSocket(SERVER_PORT);
					System.out.println("connecting...");
					Socket client = mSocketServer.accept();

					//입 출력용 스트림 준비
					InputStream is = client.getInputStream();
					OutputStream os = client.getOutputStream();

					// 입 출력용 스트림 연결
					mReaderFromClient = new BufferedReader(new InputStreamReader(is));
					mServerWriter = new BufferedWriter(new OutputStreamWriter(os));
				} catch (IOException e) {
					e.printStackTrace();
					
				}
				
				try {
					// PC로부터 들어오는 메시지 수신부
					while (true) {
						String msg = "";					
						msg = mReaderFromClient.readLine();

						if (msg.equals("exit")) {
							break;
						} else if(msg!=null&&msg!="") {
							Message message = Message.obtain(mMainHandler, MSG_FROM_CLIENT);
							message.obj = msg;
							mMainHandler.sendMessage(message);	
						}
					}
				} catch (Exception e) {
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
					break;

				default:
					break;
				}
			}
		}
	
}
