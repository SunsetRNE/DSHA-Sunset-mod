package com.deepseekharness.app.util;

/** Android network instance and its selected directly connected IPv4 route. */
public record LanNetworkState(long handle, String interfaceName, String address, int prefixLength) {
  public String description() {
    return address + "/" + prefixLength + " (" + interfaceName + ")";
  }

  public boolean eligible() {
    return handle != 0
        && interfaceName != null
        && !interfaceName.isBlank()
        && LanAddressPolicy.privateIpv4(address)
        && prefixLength <= 32
        && prefixLength >= (address.startsWith("10.") ? 8 : address.startsWith("172.") ? 12 : 16);
  }

  /** Only peers on the confirmed interface subnet may start a browser session. */
  public boolean admits(java.net.InetAddress peer) {
    if (!(peer instanceof java.net.Inet4Address) || prefixLength < 1 || prefixLength > 32)
      return false;
    int mask = -1 << (32 - prefixLength);
    return (ipv4(address) & mask) == (ipv4(peer.getHostAddress()) & mask);
  }

  private static int ipv4(String address) {
    int result = 0;
    for (String octet : address.split("\\.")) result = (result << 8) | Integer.parseInt(octet);
    return result;
  }
}
