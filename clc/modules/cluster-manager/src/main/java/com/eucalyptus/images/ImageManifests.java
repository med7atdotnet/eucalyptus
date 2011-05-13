/*******************************************************************************
 * Copyright (c) 2009  Eucalyptus Systems, Inc.
 * 
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, only version 3 of the License.
 * 
 * 
 *  This file is distributed in the hope that it will be useful, but WITHOUT
 *  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 *  FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 *  for more details.
 * 
 *  You should have received a copy of the GNU General Public License along
 *  with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 *  Please contact Eucalyptus Systems, Inc., 130 Castilian
 *  Dr., Goleta, CA 93101 USA or visit <http://www.eucalyptus.com/licenses/>
 *  if you need additional information or have any questions.
 * 
 *  This file may incorporate work covered under the following copyright and
 *  permission notice:
 * 
 *    Software License Agreement (BSD License)
 * 
 *    Copyright (c) 2008, Regents of the University of California
 *    All rights reserved.
 * 
 *    Redistribution and use of this software in source and binary forms, with
 *    or without modification, are permitted provided that the following
 *    conditions are met:
 * 
 *      Redistributions of source code must retain the above copyright notice,
 *      this list of conditions and the following disclaimer.
 * 
 *      Redistributions in binary form must reproduce the above copyright
 *      notice, this list of conditions and the following disclaimer in the
 *      documentation and/or other materials provided with the distribution.
 * 
 *    THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 *    IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 *    TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 *    PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
 *    OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 *    EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 *    PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 *    PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 *    LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 *    NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 *    SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. USERS OF
 *    THIS SOFTWARE ACKNOWLEDGE THE POSSIBLE PRESENCE OF OTHER OPEN SOURCE
 *    LICENSED MATERIAL, COPYRIGHTED MATERIAL OR PATENTED MATERIAL IN THIS
 *    SOFTWARE, AND IF ANY SUCH MATERIAL IS DISCOVERED THE PARTY DISCOVERING
 *    IT MAY INFORM DR. RICH WOLSKI AT THE UNIVERSITY OF CALIFORNIA, SANTA
 *    BARBARA WHO WILL THEN ASCERTAIN THE MOST APPROPRIATE REMEDY, WHICH IN
 *    THE REGENTS’ DISCRETION MAY INCLUDE, WITHOUT LIMITATION, REPLACEMENT
 *    OF THE CODE SO IDENTIFIED, LICENSING OF THE CODE SO IDENTIFIED, OR
 *    WITHDRAWAL OF THE CODE CAPABILITY TO THE EXTENT NEEDED TO COMPLY WITH
 *    ANY SUCH LICENSES OR RIGHTS.
 *******************************************************************************
 * @author chris grzegorczyk <grze@eucalyptus.com>
 */

package com.eucalyptus.images;

import java.io.ByteArrayInputStream;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.NoSuchElementException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.apache.log4j.Logger;
import org.w3c.dom.Document;
import com.eucalyptus.auth.Accounts;
import com.eucalyptus.auth.AuthException;
import com.eucalyptus.auth.policy.PolicySpec;
import com.eucalyptus.auth.principal.User;
import com.eucalyptus.blockstorage.WalrusUtil;
import com.eucalyptus.cloud.Image;
import com.eucalyptus.cloud.Image.Platform;
import com.eucalyptus.component.Components;
import com.eucalyptus.component.auth.SystemCredentialProvider;
import com.eucalyptus.component.id.Eucalyptus;
import com.eucalyptus.component.id.Walrus;
import com.eucalyptus.context.Context;
import com.eucalyptus.context.Contexts;
import com.eucalyptus.crypto.util.B64;
import com.eucalyptus.images.ImageManifests.ImageManifest;
import com.eucalyptus.util.EucalyptusCloudException;
import com.eucalyptus.util.FullName;
import com.eucalyptus.util.Logs;
import com.eucalyptus.util.Lookups;
import com.eucalyptus.ws.client.ServiceDispatcher;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import edu.ucsb.eucalyptus.msgs.GetBucketAccessControlPolicyResponseType;
import edu.ucsb.eucalyptus.msgs.GetBucketAccessControlPolicyType;
import edu.ucsb.eucalyptus.msgs.GetObjectResponseType;
import edu.ucsb.eucalyptus.msgs.GetObjectType;
import edu.ucsb.eucalyptus.util.XMLParser;

public class ImageManifests {
  private static Logger LOG = Logger.getLogger( ImageManifests.class );
  
  static boolean verifyBucketAcl( String bucketName ) {
    Context ctx = Contexts.lookup( );
    GetBucketAccessControlPolicyType getBukkitInfo = new GetBucketAccessControlPolicyType( );
    getBukkitInfo.setBucket( bucketName );
    try {
      GetBucketAccessControlPolicyResponseType reply = ( GetBucketAccessControlPolicyResponseType ) ServiceDispatcher.lookupSingle( Components.lookup( Walrus.class ) ).send( getBukkitInfo );
      String ownerName = reply.getAccessControlPolicy( ).getOwner( ).getDisplayName( );
      return ctx.getUserFullName( ).getAccountNumber( ).equals( ownerName ) || ctx.getUserFullName( ).getUserId( ).equals( ownerName );
    } catch ( EucalyptusCloudException ex ) {
      LOG.error( ex, ex );
    } catch ( NoSuchElementException ex ) {
      LOG.error( ex, ex );
    }
    return false;
  }
  
  private static boolean verifyManifestSignature( final X509Certificate cert, final String signature, String pad ) {
    Signature sigVerifier;
    try {
      sigVerifier = Signature.getInstance( "SHA1withRSA" );
      PublicKey publicKey = cert.getPublicKey( );
      sigVerifier.initVerify( publicKey );
      sigVerifier.update( ( pad ).getBytes( ) );
      return sigVerifier.verify( hexToBytes( signature ) );
    } catch ( Exception ex ) {
      LOG.error( ex, ex );
      return false;
    }
  }
  
  private static byte[] hexToBytes( String data ) {
    int k = 0;
    byte[] results = new byte[data.length( ) / 2];
    for ( int i = 0; i < data.length( ); ) {
      results[k] = ( byte ) ( Character.digit( data.charAt( i++ ), 16 ) << 4 );
      results[k] += ( byte ) ( Character.digit( data.charAt( i++ ), 16 ) );
      k++;
    }
    
    return results;
  }
  
  static String requestManifestData( FullName userName, String bucketName, String objectName ) throws EucalyptusCloudException {
    GetObjectResponseType reply = null;
    try {
      GetObjectType msg = new GetObjectType( bucketName, objectName, true, false, true );
//TODO:GRZE:WTF.      
//      User user = Accounts.lookupUserById( userName.getNamespace( ) );
//      msg.setUserId( user.getName( ) );
      msg.regarding( );
      msg.setCorrelationId( Contexts.lookup( ).getRequest( ).getCorrelationId( ) );
      reply = ( GetObjectResponseType ) ServiceDispatcher.lookupSingle( Components.lookup( Walrus.class ) ).send( msg );
    } catch ( Exception e ) {
      throw new EucalyptusCloudException( "Failed to read manifest file: " + bucketName + "/" + objectName, e );
    }
    return B64.url.decString( reply.getBase64Data( ).getBytes( ) );
  }
  
  public static class ImageManifest {
    private final String             imageLocation;
    private final Image.Architecture architecture;
    private final String             kernelId;
    private final String             ramdiskId;
    private final Image.Type         imageType;
    private final Image.Platform     platform;
    private final String             signature;
    private final String             manifest;
    private final Document           inputSource;
    private final String             name;
    private final Long               size        = -1l;
    private final Long               bundledSize = -1l;
    private XPath                    xpath;
    private Function<String, String> xpathHelper;
    private String                   encryptedKey;
    private String                   encryptedIV;
    
    ImageManifest( String imageLocation ) throws EucalyptusCloudException {
      Context ctx = Contexts.lookup( );
      String cleanLocation = imageLocation.replaceAll( "^/*", "" );
      this.imageLocation = cleanLocation;
      int index = cleanLocation.indexOf( '/' );
      if ( index < 2 || index + 1 >= cleanLocation.length( ) ) {
        throw new EucalyptusCloudException( "Image registration failed:  Invalid image location: " + imageLocation );
      }
      String bucketName = cleanLocation.substring( 0, index );
      String manifestKey = cleanLocation.substring( index + 1 );
      final String manifestName = manifestKey.replaceAll( ".*/", "" );
      if ( !ImageManifests.verifyBucketAcl( bucketName ) ) {
        throw new EucalyptusCloudException( "Image registration failed: you must own the bucket containing the image." );
      }
      this.manifest = ImageManifests.requestManifestData( ctx.getUserFullName( ), bucketName, manifestKey );
      try {
        DocumentBuilder builder = DocumentBuilderFactory.newInstance( ).newDocumentBuilder( );
        this.inputSource = builder.parse( new ByteArrayInputStream( this.manifest.getBytes( ) ) );
      } catch ( Exception e ) {
        throw new EucalyptusCloudException( "Failed to read manifest file: " + bucketName + "/" + manifestKey, e );
      }
      this.xpath = XPathFactory.newInstance( ).newXPath( );
      this.xpathHelper = new Function<String, String>( ) {
        
        @Override
        public String apply( String input ) {
          return ( String ) ImageManifest.this.xpath.evaluate( input, ImageManifest.this.inputSource, XPathConstants.STRING );
        }
      };
      
      String temp;
      this.name = ( ( temp = this.xpathHelper.apply( "/manifest/image/name/text()" ) ) != null )
        ? temp
        : manifestName.replace( ".manifest.xml", "" );
      try {
        this.signature = ( ( temp = this.xpathHelper.apply( "//signature" ) ) != null )
          ? temp
          : null;
      } catch ( XPathExpressionException e ) {
        LOG.warn( e.getMessage( ) );
        throw new EucalyptusCloudException( "Failed to parse manifest file for the required field:  signature.  Cause: " + e.getMessage( ), e );
      }
      this.encryptedKey = this.xpathHelper.apply( "//ec2_encrypted_key" );
      this.encryptedIV = this.xpathHelper.apply( "//ec2_encrypted_iv" );
      Predicate<Image.Type> checkIdType = new Predicate<Image.Type>( ) {
        
        @Override
        public boolean apply( Image.Type input ) {
          String value = ImageManifest.this.xpathHelper.apply( input.getManifestPath( ) );
          if ( "yes".equals( value ) || "true".equals( value ) || manifestName.startsWith( input.getNamePrefix( ) ) ) {
            return true;
          } else {
            return false;
          }
        }
      };
      String typeInManifest = this.xpathHelper.apply( Image.TYPE_MANIFEST_XPATH );
      
      this.size = ( ( temp = this.xpathHelper.apply( "/manifest/image/size/text()" ) ) != null )
        ? Long.parseLong( temp )
        : -1l;
      this.bundledSize = ( ( temp = this.xpathHelper.apply( "/manifest/image/bundled_size/text()" ) ) != null )
        ? Long.parseLong( temp )
        : -1l;
      
      String arch = this.xpathHelper.apply( "/manifest/machine_configuration/architecture/text()" );
      this.architecture = Image.Architecture.valueOf( ( ( arch == null )
          ? "i386"
            : arch ) );
      if ( ( checkIdType.apply( Image.Type.kernel ) || checkIdType.apply( Image.Type.ramdisk ) ) && !ctx.hasAdministrativePrivileges( ) ) {
        throw new EucalyptusCloudException( "Only administrators can register kernel images." );
      } else {
        if ( checkIdType.apply( Image.Type.kernel ) ) {
          this.imageType = Image.Type.kernel;
          this.platform = Image.Platform.linux;
          this.kernelId = null;
          this.ramdiskId = null;
        } else if ( checkIdType.apply( Image.Type.kernel ) ) {
          this.imageType = Image.Type.ramdisk;
          this.platform = Image.Platform.linux;
          this.kernelId = null;
          this.ramdiskId = null;
        } else {
          this.kernelId = this.xpathHelper.apply( Image.Type.kernel.getManifestPath( ) );
          this.ramdiskId = this.xpathHelper.apply( Image.Type.ramdisk.getManifestPath( ) );
          this.imageType = Image.Type.machine;
          if ( !manifestName.startsWith( Image.Platform.windows.toString( ) ) ) {
            this.platform = Image.Platform.linux;
            ImageManifests.checkPrivileges( this.kernelId );
            ImageManifests.checkPrivileges( this.ramdiskId );
          } else {
            this.platform = Image.Platform.windows;
          }
        }        
      }
    }
    
    private boolean checkManifest( User user ) throws EucalyptusCloudException {
      try {} catch ( XPathExpressionException ex1 ) {
        LOG.error( ex1 );
        throw new EucalyptusCloudException(
                                            "Failed to parse manifest file for one of the following required fields:  ec2_encrypted_iv, ec2_encrypted_key, or signature.  Cause: "
                                                + ex1.getMessage( ), ex1 );
      }
      String image = this.manifest.replaceAll( ".*<image>", "<image>" ).replaceAll( "</image>.*", "</image>" );
      String machineConfiguration = this.manifest.replaceAll( ".*<machine_configuration>", "<machine_configuration>" ).replaceAll( "</machine_configuration>.*",
                                                                                                                                   "</machine_configuration>" );
      final String pad = ( machineConfiguration + image );
      Predicate<Certificate> tryVerifyWithCert = new Predicate<Certificate>( ) {
        
        @Override
        public boolean apply( Certificate checkCert ) {
          if ( checkCert instanceof X509Certificate ) {
            X509Certificate cert = ( X509Certificate ) checkCert;
            Signature sigVerifier;
            try {
              sigVerifier = Signature.getInstance( "SHA1withRSA" );
              PublicKey publicKey = cert.getPublicKey( );
              sigVerifier.initVerify( publicKey );
              sigVerifier.update( ( pad ).getBytes( ) );
              return sigVerifier.verify( hexToBytes( ImageManifest.this.signature ) );
            } catch ( Exception ex ) {
              LOG.error( ex, ex );
              return false;
            }
          } else {
            return false;
          }
        }
      };
      Function<com.eucalyptus.auth.principal.Certificate, X509Certificate> euareToX509 = new Function<com.eucalyptus.auth.principal.Certificate, X509Certificate>( ) {
        
        @Override
        public X509Certificate apply( com.eucalyptus.auth.principal.Certificate input ) {
          return input.getX509Certificate( );
        }
      };
      
      try {
        if ( Iterables.any( Lists.transform( user.getCertificates( ), euareToX509 ), tryVerifyWithCert ) ) {
          return true;
        } else if ( tryVerifyWithCert.apply( SystemCredentialProvider.getCredentialProvider( Eucalyptus.class ).getCertificate( ) ) ) {
          return true;
        } else {
          for ( User u : Accounts.listAllUsers( ) ) {
            if ( Iterables.any( Lists.transform( u.getCertificates( ), euareToX509 ), tryVerifyWithCert ) ) {
              return true;
            }
          }
        }
      } catch ( AuthException e ) {
        throw new EucalyptusCloudException( "Invalid Manifest: Failed to verify signature because of missing (deleted?) user certificate.", e );
      }
      return false;
    }
    
    public String getSignature( ) {
      return this.signature;
    }
    
    public Image.Platform getPlatform( ) {
      return this.platform;
    }
    
    public Image.Architecture getArchitecture( ) {
      return this.architecture;
    }
    
    public String getKernelId( ) {
      return this.kernelId;
    }
    
    public String getRamdiskId( ) {
      return this.ramdiskId;
    }
    
    public Image.Type getImageType( ) {
      return this.imageType;
    }
    
    public String getImageLocation( ) {
      return this.imageLocation;
    }
    
    public String getManifest( ) {
      return this.manifest;
    }
    
    public String getName( ) {
      return this.name;
    }
    
    public Long getSize( ) {
      return this.size;
    }
    
    public Long getBundledSize( ) {
      return this.bundledSize;
    }
    
  }
  
  public static ImageManifest lookup( String imageLocation ) throws EucalyptusCloudException {
    return new ImageManifest( imageLocation );
  }
  
  static void checkPrivileges( String diskId ) throws EucalyptusCloudException {
    Context ctx = Contexts.lookup( );
    if ( diskId != null ) {
      ImageInfo disk = null;
      try {
        disk = Images.lookupImage( diskId );
      } catch ( Exception ex ) {
        LOG.error( ex, ex );
        throw new EucalyptusCloudException( "Referenced image id is invalid: " + diskId, ex );
      }
      if ( !Lookups.checkPrivilege( ctx.getRequest( ), PolicySpec.VENDOR_EC2, PolicySpec.EC2_RESOURCE_IMAGE, diskId, disk.getOwner( ) ) ) {
        throw new EucalyptusCloudException( "Access to " + disk.getImageType( ).toString( ) + " image " + diskId + " is denied for " + ctx.getUser( ).getName( ) );
      }
    }
  }
}
