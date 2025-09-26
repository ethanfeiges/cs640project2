package edu.wisc.cs.sdn.vnet.sw;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.MACAddress;
import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Aaron Gember-Jacobson
 */
public class Switch extends Device
{	
	/** MAC address learning table - maps MAC addresses to interface and timestamp */
	private Map<MACAddress, MACTableEntry> macTable;
	
	/** Timeout for MAC address entries in milliseconds (15 seconds) */
	private static final long MAC_TIMEOUT = 15000;
	
	/**
	 * Helper class to store MAC table entries with interface and timestamp
	 */
	private class MACTableEntry {
		private Iface iface;
		private long timestamp;
		
		public MACTableEntry(Iface iface, long timestamp) {
			this.iface = iface;
			this.timestamp = timestamp;
		}
		
		public Iface getInterface() {
			return iface;
		}
		
		public long getTimestamp() {
			return timestamp;
		}
		
		public void updateTimestamp(long timestamp) {
			this.timestamp = timestamp;
		}
	}
	
	/**
	 * Creates a switch for a specific host.
	 * @param host hostname for the switch
	 */
	public Switch(String host, DumpFile logfile)
	{
		super(host,logfile);
		this.macTable = new ConcurrentHashMap<>();
	}
	
	/**
	 * Remove expired MAC address entries from the MAC table
	 */
	private void cleanupMACTable() {
		long currentTime = System.currentTimeMillis();
		macTable.entrySet().removeIf(entry -> 
			currentTime - entry.getValue().getTimestamp() > MAC_TIMEOUT);
	}

	/**
	 * Handle an Ethernet packet received on a specific interface.
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the interface on which the packet was received
	 */
	public void handlePacket(Ethernet etherPacket, Iface inIface)
	{
		System.out.println("*** SWITCH DEBUG: handlePacket called! ***");
		System.out.println("*** -> Received packet: " +
				etherPacket.toString().replace("\n", "\n\t"));
		
		/********************************************************************/
		/* TODO: Handle packets                                             */
		
		// Clean up expired MAC table entries
		cleanupMACTable();
		
		// Learn: Update MAC table with source MAC address and ingress interface
		MACAddress sourceMac = etherPacket.getSourceMAC();
		long currentTime = System.currentTimeMillis();
		
		// Check if we already know this MAC address
		MACTableEntry existingEntry = macTable.get(sourceMac);
		if (existingEntry != null) {
			// Update timestamp for existing entry
			existingEntry.updateTimestamp(currentTime);
		} else {
			// Add new entry to MAC table
			macTable.put(sourceMac, new MACTableEntry(inIface, currentTime));
		}
		
		// Forward: Determine where to send the packet
		MACAddress destMac = etherPacket.getDestinationMAC();
		MACTableEntry destEntry = macTable.get(destMac);
		
		if (destEntry != null) {
			// We know where this destination MAC is - send directly to that interface
			Iface outIface = destEntry.getInterface();
			// Don't send packet back out the interface it came from
			if (!outIface.equals(inIface)) {
				System.out.println("*** -> Forwarding packet out interface: " + outIface.getName());
				sendPacket(etherPacket, outIface);
			}
		} else {
			// We don't know where this destination MAC is - flood to all interfaces except ingress
			System.out.println("*** -> Flooding packet to all interfaces except " + inIface.getName());
			for (Iface iface : interfaces.values()) {
				// Don't send packet back out the interface it came from
				if (!iface.equals(inIface)) {
					System.out.println("*** -> Flooding packet out interface: " + iface.getName());
					sendPacket(etherPacket, iface);
				}
			}
		}
		
		/********************************************************************/
	}
}
