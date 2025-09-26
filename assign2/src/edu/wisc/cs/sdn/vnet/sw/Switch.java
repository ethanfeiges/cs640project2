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
		if (etherPacket == null || inIface == null) {
			System.out.println("*** -> ERROR: Null packet or interface, returning");
			return;
		}
		
		// Get packet details
		short etherType = etherPacket.getEtherType();
		MACAddress sourceMac = etherPacket.getSourceMAC();
		MACAddress destMac = etherPacket.getDestinationMAC();
		
		// Only show detailed debug for IPv4 packets (0x0800 = 2048) to reduce noise
		boolean isIPv4 = (etherType == 0x0800);
		
		if (isIPv4) {
			System.out.println("*** SWITCH DEBUG: IPv4 packet on " + inIface.getName() + " ***");
			System.out.println("*** -> EtherType: " + etherType + " (IPv4)");
			System.out.println("*** -> Source MAC: " + sourceMac);
			System.out.println("*** -> Dest MAC: " + destMac);
			System.out.println("*** -> Received packet: " +
					etherPacket.toString().replace("\n", "\n\t"));
		} else {
			// Just log non-IPv4 packets briefly
			System.out.println("*** Non-IPv4 packet: EtherType=" + etherType + " on " + inIface.getName());
		}
		
		/********************************************************************/
		/* TODO: Handle packets                                             */
		
		// Clean up expired MAC table entries
		cleanupMACTable();
		
		// Learn: Update MAC table with source MAC address and ingress interface
		long currentTime = System.currentTimeMillis();
		
		// Check if we already know this MAC address
		MACTableEntry existingEntry = macTable.get(sourceMac);
		if (existingEntry != null) {
			// Update timestamp for existing entry
			existingEntry.updateTimestamp(currentTime);
			if (isIPv4) {
				System.out.println("*** -> Updated MAC table entry for " + sourceMac + " on " + inIface.getName());
			}
		} else {
			// Add new entry to MAC table
			macTable.put(sourceMac, new MACTableEntry(inIface, currentTime));
			if (isIPv4) {
				System.out.println("*** -> Learned new MAC " + sourceMac + " on " + inIface.getName());
			}
		}
		
		// Forward: Determine where to send the packet
		MACTableEntry destEntry = macTable.get(destMac);
		
		if (destEntry != null) {
			// We know where this destination MAC is - send directly to that interface
			Iface outIface = destEntry.getInterface();
			// Don't send packet back out the interface it came from
			if (!outIface.equals(inIface)) {
				if (isIPv4) {
					System.out.println("*** -> Forwarding to known MAC " + destMac + " out interface: " + outIface.getName());
				}
				sendPacket(etherPacket, outIface);
			} else if (isIPv4) {
				System.out.println("*** -> Not forwarding packet back to same interface " + inIface.getName());
			}
		} else {
			// We don't know where this destination MAC is - flood to all interfaces except ingress
			if (isIPv4) {
				System.out.println("*** -> Unknown MAC " + destMac + ", flooding to all interfaces except " + inIface.getName());
			}
			for (Iface iface : interfaces.values()) {
				// Don't send packet back out the interface it came from
				if (!iface.equals(inIface)) {
					if (isIPv4) {
						System.out.println("*** -> Flooding packet out interface: " + iface.getName());
					}
					sendPacket(etherPacket, iface);
				}
			}
		}
		
		/********************************************************************/
	}
}
