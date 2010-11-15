package com.eucalyptus.auth.policy.key;

import java.util.Map;
import com.google.common.collect.Maps;

/**
 * IAM condition key constants.
 * 
 * @author wenye
 *
 */
public class Keys {

  public static final String AWS_CURRENTTIME = "aws:currenttime";
  
  public static final String EC2_VMNUMBER = "ec2:vmnumber";
  
  public static final Map<String, Key> KEY_MAP = Maps.newHashMap( );
  
  static {
    KEY_MAP.put( AWS_CURRENTTIME, new CurrentTime( ) );
    
    KEY_MAP.put( EC2_VMNUMBER, new VmNumber( ) );
  }
  
}
