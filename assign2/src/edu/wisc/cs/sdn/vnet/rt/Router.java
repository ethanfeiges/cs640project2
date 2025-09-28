package edu.wisc.cs.sdn.vnet.rt;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;

/**
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 */
public class Router extends Device
{	
	/** Routing table for the router */
	private RouteTable routeTable;
	
	/** ARP cache for the router */
	private ArpCache arpCache;
	
	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Router(String host, DumpFile logfile)
	{
		super(host,logfile);
		this.routeTable = new RouteTable();
		this.arpCache = new ArpCache();
	}
	
	/**
	 * @return routing table for the router
	 */
	public RouteTable getRouteTable()
	{ return this.routeTable; }
	
	/**
	 * Load a new routing table from a file.
	 * @param routeTableFile the name of the file containing the routing table
	 */
	public void loadRouteTable(String routeTableFile)
	{
		if (!routeTable.load(routeTableFile, this))
		{
			System.err.println("Error setting up routing table from file "
					+ routeTableFile);
			System.exit(1);
		}
		
		System.out.println("Loaded static route table");
		System.out.println("-------------------------------------------------");
		System.out.print(this.routeTable.toString());
		System.out.println("-------------------------------------------------");
	}
	
	/**
	 * Load a new ARP cache from a file.
	 * @param arpCacheFile the name of the file containing the ARP cache
	 */
	public void loadArpCache(String arpCacheFile)
	{
		if (!arpCache.load(arpCacheFile))
		{
			System.err.println("Error setting up ARP cache from file "
					+ arpCacheFile);
			System.exit(1);
		}
		
		System.out.println("Loaded static ARP cache");
		System.out.println("----------------------------------");
		System.out.print(this.arpCache.toString());
		System.out.println("----------------------------------");
	}

	/**
	 * Handle an Ethernet packet received on a specific interface.
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the interface on which the packet was received
	 */
	public void handlePacket(Ethernet etherPacket, Iface inIface)
	{
		System.out.println("*** -> Received packet: " +
				etherPacket.toString().replace("\n", "\n\t"));
		
		// Step 1: Check if the packet contains an IPv4 packet
		if (etherPacket.getEtherType() != Ethernet.TYPE_IPv4)
		{
			// Drop non-IPv4 packets
			return;
		}
		
		// Step 2: Extract the IPv4 packet from the Ethernet frame
		IPv4 ipPacket = (IPv4) etherPacket.getPayload();
		if (ipPacket == null)
		{
			// Drop if payload is not IPv4
			return;
		}
		
		// Step 3: Verify the checksum
		// Save the original checksum
		short originalChecksum = ipPacket.getChecksum();
		
		// Zero the checksum field for verification
		ipPacket.setChecksum((short) 0);
		
		// Calculate the checksum over the IP header
		byte[] headerData = new byte[ipPacket.getHeaderLength() * 4];
		byte[] fullData = ipPacket.serialize();
		System.arraycopy(fullData, 0, headerData, 0, headerData.length);
		
		// Calculate checksum manually
		int accumulation = 0;
		for (int i = 0; i < headerData.length; i += 2)
		{
			int word = ((headerData[i] & 0xFF) << 8) | (headerData[i + 1] & 0xFF);
			accumulation += word;
		}
		accumulation = ((accumulation >> 16) & 0xFFFF) + (accumulation & 0xFFFF);
		short calculatedChecksum = (short) (~accumulation & 0xFFFF);
		
		// Restore the original checksum
		ipPacket.setChecksum(originalChecksum);
		
		// Verify checksum
		if (originalChecksum != calculatedChecksum)
		{
			// Drop packet with incorrect checksum
			return;
		}
		
		// Step 4: Check and decrement TTL
		byte ttl = ipPacket.getTtl();
		ttl--;
		if (ttl <= 0)
		{
			// Drop packet with TTL = 0
			return;
		}
		ipPacket.setTtl(ttl);
		
		// Step 5: Check if packet is destined for one of the router's interfaces
		int destinationIp = ipPacket.getDestinationAddress();
		for (Iface iface : this.interfaces.values())
		{
			if (iface.getIpAddress() == destinationIp)
			{
				// Drop packet destined for this router
				return;
			}
		}
		
		// Step 6: Forward the packet
		// Look up route entry with longest prefix match
		RouteEntry routeEntry = this.routeTable.lookup(destinationIp);
		if (routeEntry == null)
		{
			// Drop packet if no route found
			return;
		}
		
		// Determine next-hop IP address
		int nextHopIp = routeEntry.getGatewayAddress();
		if (nextHopIp == 0)
		{
			// Direct route - next hop is the destination itself
			nextHopIp = destinationIp;
		}
		
		// Look up MAC address in ARP cache
		ArpEntry arpEntry = this.arpCache.lookup(nextHopIp);
		if (arpEntry == null)
		{
			// Drop packet if MAC address not found in ARP cache
			return;
		}
		
		// Get outgoing interface
		Iface outIface = routeEntry.getInterface();
		
		// Update Ethernet header
		// Set destination MAC to next-hop MAC
		etherPacket.setDestinationMACAddress(arpEntry.getMac().toBytes());
		// Set source MAC to outgoing interface MAC
		etherPacket.setSourceMACAddress(outIface.getMacAddress().toBytes());
		
		// Reset IP checksum so it gets recalculated with new TTL
		ipPacket.resetChecksum();
		
		// Send the packet out the correct interface
		this.sendPacket(etherPacket, outIface);
		
		/********************************************************************/
	}
}
