/*
 * Created on Feb 9, 2005
 * Created by Alon Rohter
 * Copyright (C) Azureus Software, Inc, All Rights Reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 *
 */

package pluginsimpl.local.network;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.spec.AlgorithmParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import plugins.messaging.MessageStreamDecoder;
import plugins.messaging.MessageStreamEncoder;
import plugins.network.Connection;
import plugins.network.ConnectionManager;
import plugins.network.RateLimiter;
import plugins.network.Transport;
import plugins.network.TransportCipher;
import plugins.network.TransportException;
import plugins.network.TransportFilter;
import pluginsimpl.local.messaging.MessageStreamDecoderAdapter;
import pluginsimpl.local.messaging.MessageStreamEncoderAdapter;

import controller.networkmanager.ConnectionEndpoint;
import controller.networkmanager.NetworkManager;
import controller.networkmanager.ProtocolEndpoint;
import controller.networkmanager.ProtocolEndpointFactory;
import controller.networkmanager.TransportHelper;
import controller.networkmanager.TransportHelperFilter;
import controller.networkmanager.udp.UDPNetworkManager;
import controller.networkmanager.TransportHelperFilterStreamCipher;
import controller.networkmanager.tcp.ProtocolEndpointTCP;
import controller.networkmanager.tcp.TCPTransportHelper;
import controller.networkmanager.tcp.TCPTransportImpl;
import controller.networkmanager.udp.UDPTransport;
import controller.networkmanager.udp.UDPTransportHelper;
import torrentlib.TorrentEngineCore;


/**
 *
 */
public class ConnectionManagerImpl implements ConnectionManager {
  
  private static ConnectionManagerImpl instance;
  
  
  public static synchronized ConnectionManagerImpl 
  getSingleton(
	TorrentEngineCore		core )
  {
	  if ( instance == null ){
		  
		  instance = new ConnectionManagerImpl( core );
	  }
	  
	  return( instance );
  }
  
  private TorrentEngineCore		azureus_core;
  
  private ConnectionManagerImpl(TorrentEngineCore _core) {
  
	  azureus_core	= _core;
  }
  

  public Connection 
  createConnection( 
	InetSocketAddress remote_address, 
	MessageStreamEncoder encoder, 
	MessageStreamDecoder decoder ) 
  {
	  ConnectionEndpoint connection_endpoint	= new ConnectionEndpoint( remote_address );
	  
	  connection_endpoint.addProtocol( ProtocolEndpointFactory.createEndpoint( ProtocolEndpoint.PROTOCOL_TCP, remote_address ));
	 
	   controller.networkmanager.NetworkConnection core_conn =
		  NetworkManager.getSingleton().createConnection( connection_endpoint, new MessageStreamEncoderAdapter( encoder ), new MessageStreamDecoderAdapter( decoder ), false, false, null );
    
	  return new ConnectionImpl( core_conn, false );
  }
  
  public int
  getNATStatus()
  {
	  return( azureus_core.getGlobalManager().getNATStatus());
  }
  
  public TransportCipher createTransportCipher(String algorithm, int mode, SecretKeySpec key_spec, AlgorithmParameterSpec params) throws TransportException {
	  try {
		   controller.networkmanager.TransportCipher cipher = new controller.networkmanager.TransportCipher(algorithm, mode, key_spec, params);
		  return new TransportCipherImpl(cipher);
	  }
	  catch (Exception e) {
		  throw new TransportException(e);
	  }
  }
  
  public TransportFilter createTransportFilter(Connection connection, TransportCipher read_cipher, TransportCipher write_cipher) throws TransportException {
	  Transport transport = connection.getTransport();
	  if ( transport == null ){
		  throw( new TransportException( "no transport available" )); 
	  }
	   controller.networkmanager.Transport core_transport;
	  try {core_transport = ((TransportImpl)transport).coreTransport();}
	  catch (IOException e) {throw new TransportException(e);}
	  
	  TransportHelper helper;
	  
	  if (core_transport instanceof TCPTransportImpl) {
		  TransportHelperFilter hfilter = ((TCPTransportImpl)core_transport).getFilter();
		  if (hfilter != null) {helper = hfilter.getHelper();}
		  else {
			  helper = new TCPTransportHelper(((TCPTransportImpl)(core_transport)).getSocketChannel());
		  }
	  } else if (core_transport instanceof UDPTransport) {
		  TransportHelperFilter hfilter = ((UDPTransport)core_transport).getFilter();
		  if (hfilter != null) {helper = hfilter.getHelper();}
		  else {
			  helper = ((UDPTransport)core_transport).getFilter().getHelper();
			  InetSocketAddress addr = core_transport.getTransportEndpoint().getProtocolEndpoint().getConnectionEndpoint().getNotionalAddress();
			  if (!connection.isIncoming()) {
				try {helper = new UDPTransportHelper(UDPNetworkManager.getSingleton().getConnectionManager(), addr, (UDPTransport)core_transport);}
				catch (IOException ioe) {throw new TransportException(ioe);}
			  }
			  else {
				/**
				 * Not sure how I can grab the UDPConnection object to pass to the incoming
				 * connection constructor. The only time I can figure out where we can link
				 * up the UDPConnection object is in UDPConnectionManager.accept - we have a
				 * transport object and we construct a UDPConnection object, so we could link
				 * them there - but I don't know if we really should associate the UDP connection
				 * with the transport (might breaks encapsulation).
				 */
				  //helper = new UDPTransportHelper(UDPNetworkManager.getSingleton().getConnectionManager(), addr, (UDPTransport)core_transport);
				  throw new TransportException("udp incoming transport type not supported - " + core_transport);
			  }
		  }
		} else {
			throw new TransportException("transport type not supported - " + core_transport);
	  }

	  TransportHelperFilterStreamCipher core_filter = new TransportHelperFilterStreamCipher(helper, ((TransportCipherImpl)read_cipher).cipher, ((TransportCipherImpl)write_cipher).cipher);
	  return new TransportFilterImpl(core_filter);
  }
  
  public RateLimiter 
  createRateLimiter(
	String 	name, 
	int 	bps ) 
  {
	  return( new PluginRateLimiter( name, bps ));
	
  }
  
  public class
  PluginRateLimiter
	implements RateLimiter
  {
		private String		name;
		private int			rate;
			
		private long		total;
		
		private
		PluginRateLimiter(
			String		_name,
			int			_bps )
		{
			name	= _name;
			rate	= _bps;
		}
		
		public String
		getName()
		{
			return( name );
		}
		
		public int 
		getRateLimitBytesPerSecond()
		{
			return( rate );
		}
		
		public void
		setRateLimitBytesPerSecond(
			int		bytes_per_second )
		{
			rate = bytes_per_second;
		}
		
		public long 
		getRateLimitTotalByteCount() 
		{
			return( total );
		}
		
		public void
		updateBytesUsed(
			int	used )
		{  
			total += used;
		}
	}
}
