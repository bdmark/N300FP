/**
 * The MIT License
 * -------------------------------------------------------------
 * Copyright (c) 2008, Rob Ellis, Brock Whitten, Brian Leroux, Joe Bowser, Dave Johnson, Nitobi
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.phonegap.api.impl;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.microedition.io.Connector;
import javax.microedition.io.HttpConnection;
import javax.microedition.io.StreamConnection;

import net.rim.device.api.system.CoverageInfo;
import net.rim.device.api.system.DeviceInfo;
import net.rim.device.api.system.RadioInfo;
import net.rim.device.api.ui.UiApplication;

import com.phonegap.PhoneGap;
import com.phonegap.api.Command;

public class NetworkCommand implements Command {

	private static final int REACHABLE_COMMAND = 0;
	private static final int XHR_COMMAND = 1;
	private static final String CODE = "PhoneGap=network";
	private static final int NOT_REACHABLE = 0;
	private static final int REACHABLE_VIA_CARRIER_DATA_NETWORK = 1;
	private static final int REACHABLE_VIA_WIFI_NETWORK = 2;
	public PhoneGap berryGap;
	private ConnectionThread connThread = new ConnectionThread();

	public NetworkCommand(PhoneGap gap) {
		berryGap = gap;
		connThread.start();
	}

	/**
	 * Determines whether the specified instruction is accepted by the command. 
	 * @param instruction The string instruction passed from JavaScript via cookie.
	 * @return true if the Command accepts the instruction, false otherwise.
	 */
	public boolean accept(String instruction) {
		return instruction != null && instruction.startsWith(CODE);
	}
	
	public String execute(String instruction) {
		switch (getCommand(instruction)) {
			case REACHABLE_COMMAND:
				// Determine the active Wireless Access Families
				// WiFi will have precedence over carrier data.
				int service = RadioInfo.getActiveWAFs();
				int reachability = NOT_REACHABLE;
				if ((service & RadioInfo.WAF_3GPP) != 0 ||
						(service & RadioInfo.WAF_CDMA) != 0 ||
						(service & RadioInfo.WAF_IDEN) != 0) {
					reachability = REACHABLE_VIA_CARRIER_DATA_NETWORK;
				}
				if ((service & RadioInfo.WAF_WLAN) != 0) {
					reachability = REACHABLE_VIA_WIFI_NETWORK;
				}
				return ";navigator.network.lastReachability = " + reachability + ";if (navigator.network.isReachable_success) navigator.network.isReachable_success("+reachability+");";
				
			case XHR_COMMAND:
				String reqURL = instruction.substring(CODE.length()+5);
				String POSTdata = null;
				int pipeIndex = reqURL.indexOf("|");
				if (pipeIndex > -1) {
					POSTdata = reqURL.substring(pipeIndex+1);
					reqURL = reqURL.substring(0,pipeIndex);
				}
				// Something that is used by the BlackBerry Enterprise Server for the BES Push apps. We want to initiate a direct TCP connection, so this parameter needs to be specified. 
				if (!DeviceInfo.isSimulator()) {
					reqURL += ";deviceside=true";
				}
				// Check for WIFI connectivity, optionally append the interface=wifi parameter to the end of URL.
				// If you have data disabled and WIFI enabled, but you cannot access the network, then check that
				// the device is not configured to connect to a VPN network.
				// WIFI Connection > WIFI Options > Select the active network > Edit > Set VPN to None
				if ((RadioInfo.getActiveWAFs() & RadioInfo.WAF_WLAN) != 0) {
					if (CoverageInfo.isCoverageSufficient(CoverageInfo.COVERAGE_DIRECT, RadioInfo.WAF_WLAN, true)) {
						reqURL += ";interface=wifi";
					}
				}
				connThread.fetch(reqURL, POSTdata);
				reqURL = null;
				POSTdata = null;
		        break;
		}
		return null;
	}
	private int getCommand(String instruction) {
		String command = instruction.substring(CODE.length()+1);
		if (command.startsWith("reach")) return REACHABLE_COMMAND;
		if (command.startsWith("xhr")) return XHR_COMMAND;
		return -1;
	}
	/**
	 * Adds the specified text to the PhoneGap response queue. For use by asynchronous XHR requests.
	 */
	private void updateContent(final String text)
    {
        UiApplication.getUiApplication().invokeLater(new Runnable() 
        {
            public void run()
            {
            	berryGap.pendingResponses.addElement(text);
            }
        });
    }
	public void stopXHR() {
		connThread._stop = true;
	}
	private class ConnectionThread extends Thread
    {
        private static final int TIMEOUT = 500; // ms

        private String _theUrl;
        private String _POSTdata;

        private volatile boolean _fetchStarted = false;
        public volatile boolean _stop = false;

        // Retrieve the URL.
        private synchronized String getUrl()
        {
            return _theUrl;
        }
        private synchronized String getPOSTdata()
        {
            return _POSTdata;
        }
        
        // Fetch a page.
        // Synchronized so that we don't miss requests.
        private void fetch(String url, String POSTdata)
        {
            synchronized(this)
            {
                _fetchStarted = true;
                _theUrl = url;
                _POSTdata = POSTdata;
            }
        }

        public void run()
        {
            for(;;)
            {
            	_stop = false;
                // Thread control
                while( !_fetchStarted && !_stop)  
                {
                    // Sleep for a bit so we don't spin.
                    try 
                    {
                        sleep(TIMEOUT);
                    } 
                    catch (InterruptedException e) 
                    {
                        System.err.println(e.toString());
                    }
                }
                
                // Exit condition
                if ( _stop )
                {
                    continue;
                }
                
                // This entire block is synchronized. This ensures we won't miss fetch requests
                // made while we process a page.
                synchronized(this)
                {                    
                    String content = "";
                    HttpConnection httpConn = null;
                    StreamConnection s = null;
                    String postData = getPOSTdata();
                    // Open the connection and extract the data.
                    try 
                    {                        
                        if (postData != null) {
                        	s = (StreamConnection)Connector.open(getUrl(), Connector.READ_WRITE);
                        } else {
                        	s = (StreamConnection)Connector.open(getUrl());
                        }
                        httpConn = (HttpConnection)s;  
                        httpConn.setRequestMethod((postData != null)?HttpConnection.POST:HttpConnection.GET);
						// === SET HTTP REQUEST HEADERS HERE ===
						// Set the user agent string. Could try to parse out device models/numbers, but do I really want to? Yes, I do, according to this KB article:
						// http://www.blackberry.com/knowledgecenterpublic/livelink.exe/fetch/2000/348583/800451/800563/How_To_-_Use_reliable_push_without_making_a_BlackBerry_Browser_request.html?nodeid=1222784&vernum=0
                        httpConn.setRequestProperty("user-agent", "BlackBerry" + DeviceInfo.getDeviceName() + "/" + DeviceInfo.getSoftwareVersion());
						// Also need to set profile header according to above article.
						httpConn.setRequestProperty("profile", "http://www.blackberry.net/go/mobile/profiles/uaprof/" + DeviceInfo.getDeviceName() + "/" + DeviceInfo.getSoftwareVersion() + ".rdf");
                        httpConn.setRequestProperty("Content-Type","text/plain,text/html,application/rss+xml,application/x-www-form-urlencoded");
						// Here's an example of setting the Accept header to a particular subset of MIME types. By the HTTP spec, if none is specified the assumed value is 'all' types are accepted.
                        //httpConn.setRequestProperty("Accept","text/plain,text/html,application/rss+xml,text/javascript,text/xml");
						// Setting the accepted character set. Same as above, default is all, so don't have to set it. 
                        //httpConn.setRequestProperty("Accept-Charset","UTF-8,*");
						// === WRITE OUT POST DATA HERE ===
                        if (postData != null) {
                        	httpConn.setRequestProperty("Content-length", String.valueOf(postData.length()));
                        	DataOutputStream dos = httpConn.openDataOutputStream();
                        	byte[] postBytes = postData.getBytes();
                        	for (int i = 0; i < postBytes.length; i++) {
                        		dos.writeByte(postBytes[i]);
                        	}
                        	dos.flush();
                        	dos.close();
                        	dos = null;
                        }
                        int status = httpConn.getResponseCode();
						// Tip: If you're not getting the expected response from an XHR call, pop a breakpoint here and see if the HTTP response code is 200. If you're getting a 406 (Not Acceptable), the Accept header might be not set to some satisfactory value by the server.
                        if (status == HttpConnection.HTTP_OK)
                        {
                            InputStream input = s.openInputStream();
                            byte[] data = new byte[256];
                            int len = 0;
                            int size = 0;
                            StringBuffer raw = new StringBuffer();
                            
                            while ( -1 != (len = input.read(data)) )
                            {
                                raw.append(new String(data, 0, len));
                                size += len;
                            }             
                            content = raw.toString();
                            raw = null;
                            input.close();        
                            input = null;
                        } 
                        if (_stop) continue;
                        updateContent(";if (navigator.network.XHR_success) { navigator.network.XHR_success(" + (!content.equals("")?content:"{error:true,message:'Bad server response.',httpcode:" + status + "}") + "); };");
                        s.close();                    
                    } 
                    catch (IOException e) 
                    {            
                    	if (_stop) continue;
                    	String resp = e.getMessage().toLowerCase();
                    	if (content.equals("")) {
                    		if (resp.indexOf("tunnel") > -1 || resp.indexOf("Tunnel") > -1 || resp.indexOf("apn") > -1 || resp.indexOf("APN") > -1) {
                    			resp = "{error:true,message:'There was a communication error. Are your APN settings configured (BlackBerry menu -> Options -> Advanced Options -> TCP/IP). Contact your service provider for details on how to set up your APN settings.'}";
                    		} else {
                    			resp = "{error:true,message:'IOException during HTTP request: " + e.getMessage().replace('\'', ' ') + "',httpcode:null}";
                    		}
                    	} else {
                    		resp = content;
                    	}
                    	updateContent(";if (navigator.network.XHR_success) { navigator.network.XHR_success(" + resp + "); };");
                    	resp = null;
                    } finally {
                    	content = null;
                    	s = null;
                    	httpConn = null;
                    	postData = null;
                    }
                    // We're finished with the operation so reset the start state.
                    _fetchStarted = false;
                }
            }
        }
    }
}