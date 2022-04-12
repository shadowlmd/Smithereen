package smithereen.activitypub.objects;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.Collections;

import smithereen.Config;
import smithereen.Utils;
import smithereen.activitypub.ContextCollector;
import smithereen.activitypub.ParserContext;
import smithereen.data.CachedRemoteImage;
import smithereen.data.NonCachedRemoteImage;
import smithereen.data.SizedImage;
import smithereen.exceptions.BadRequestException;
import smithereen.jsonld.JLD;
import smithereen.storage.MediaCache;
import spark.utils.StringUtils;

public abstract class Actor extends ActivityPubObject{
	public String username;
	transient public PublicKey publicKey;
	transient public PrivateKey privateKey;
	public String domain;
	public URI inbox;
	public URI outbox;
	public URI sharedInbox;
	public URI followers;
	public URI following;
	public Timestamp lastUpdated;

	public String aboutSource;

	public String getProfileURL(String action){
		return "/"+getFullUsername()+"/"+action;
	}

	public String getProfileURL(){
		return "/"+getFullUsername();
	}

	public boolean hasAvatar(){
		return icon!=null;
	}

	public Image getBestAvatarImage(){
		Image icon=this.icon!=null ? this.icon.get(0) : null;
		if(icon==null)
			return null;
		if(icon instanceof LocalImage)
			return icon;
		if(icon.image!=null && !icon.image.isEmpty() && icon.image.get(0).width>0 && icon.image.get(0).height>0)
			return icon.image.get(0);
		return icon;
	}

	public float[] getAvatarCropRegion(){
		Image icon=this.icon!=null ? this.icon.get(0) : null;
		if(icon==null)
			return null;
		return icon.cropRegion;
	}

	public String getFullUsername(){
		return username;
	}

	public URI getFollowersURL(){
		String userURL=activityPubID.toString();
		return URI.create(userURL+"/followers");
	}

	public SizedImage getAvatar(){
		Image icon=getBestAvatarImage();
		if(icon==null)
			return null;
		if(icon instanceof LocalImage){
			return (LocalImage) icon;
		}
		MediaCache cache=MediaCache.getInstance();
		try{
			MediaCache.PhotoItem item=(MediaCache.PhotoItem) cache.get(icon.url);
			if(item!=null){
				return new CachedRemoteImage(item, getAvatarCropRegion());
			}else{
				SizedImage.Dimensions size=SizedImage.Dimensions.UNKNOWN;
				if(icon.width>0 && icon.height>0){
					size=new SizedImage.Dimensions(icon.width, icon.height);
				}
				return new NonCachedRemoteImage(getAvatarArgs(), size);
			}
		}catch(SQLException e){
			e.printStackTrace();
		}
		return null;
	}

	protected NonCachedRemoteImage.Args getAvatarArgs(){
		throw new IllegalStateException("Should never happen");
	}

	@Override
	public JsonObject asActivityPubObject(JsonObject obj, ContextCollector contextCollector){
		obj=super.asActivityPubObject(obj, contextCollector);

		String userURL=activityPubID.toString();
		obj.addProperty("preferredUsername", username);
		obj.addProperty("inbox", userURL+"/inbox");
		obj.addProperty("outbox", userURL+"/outbox");
		if(canBeFollowed())
			obj.addProperty("followers", getFollowersURL().toString());
		if(canFollowOtherActors())
			obj.addProperty("following", userURL+"/following");

		JsonObject endpoints=new JsonObject();
		endpoints.addProperty("sharedInbox", Config.localURI("/activitypub/sharedInbox").toString());
		obj.add("endpoints", endpoints);

		JsonObject pubkey=new JsonObject();
		pubkey.addProperty("id", userURL+"#main-key");
		pubkey.addProperty("owner", userURL);
		String pkey="-----BEGIN PUBLIC KEY-----\n";
		pkey+=Base64.getEncoder().encodeToString(publicKey.getEncoded());
		pkey+="\n-----END PUBLIC KEY-----\n";
		pubkey.addProperty("publicKeyPem", pkey);
		obj.add("publicKey", pubkey);

		URI wallUrl=getWallURL();
		if(wallUrl!=null){
			obj.addProperty("wall", wallUrl.toString());
			contextCollector.addType("wall", "sm:wall", "@id");
			contextCollector.addAlias("sm", JLD.SMITHEREEN);
		}

		contextCollector.addSchema(JLD.W3_SECURITY);

		return obj;
	}

	@Override
	protected ActivityPubObject parseActivityPubObject(JsonObject obj, ParserContext parserContext){
		super.parseActivityPubObject(obj, parserContext);
		if(activityPubID==null)
			throw new IllegalArgumentException("id is required for actors");

		username=optString(obj, "preferredUsername");
		if(username==null){
			username=Utils.getLastPathSegment(activityPubID);
		}
		domain=activityPubID.getHost();
		if(activityPubID.getPort()!=-1)
			domain+=":"+activityPubID.getPort();
		if(url==null)
			url=activityPubID;

		JsonObject pkey=obj.getAsJsonObject("publicKey");
		URI keyOwner=tryParseURL(pkey.get("owner").getAsString());
		if(!keyOwner.equals(activityPubID))
			throw new IllegalArgumentException("Key owner ("+keyOwner+") is not equal to user ID ("+activityPubID+")");
		String pkeyEncoded=pkey.get("publicKeyPem").getAsString();
		pkeyEncoded=pkeyEncoded.replaceAll("-----(BEGIN|END) (RSA )?PUBLIC KEY-----", "").replaceAll("[^A-Za-z0-9+/=]", "").trim();
		byte[] key=Base64.getDecoder().decode(pkeyEncoded);
		try{
			X509EncodedKeySpec spec=new X509EncodedKeySpec(key);
			publicKey=KeyFactory.getInstance("RSA").generatePublic(spec);
		}catch(InvalidKeySpecException x){
			// a simpler RSA key format, used at least by Misskey
			// FWIW, Misskey user objects also contain a key "isCat" which I ignore
			try{
				RSAPublicKeySpec spec=decodeSimpleRSAKey(key);
				publicKey=KeyFactory.getInstance("RSA").generatePublic(spec);
			}catch(NoSuchAlgorithmException ignore){
			}catch(InvalidKeySpecException|IOException xx){
				throw new BadRequestException(xx);
			}
		}catch(NoSuchAlgorithmException ignore){}

		inbox=tryParseURL(obj.get("inbox").getAsString());
		ensureHostMatchesID(inbox, "inbox");
		outbox=tryParseURL(optString(obj, "outbox"));
		ensureHostMatchesID(outbox, "outbox");
		followers=tryParseURL(optString(obj, "followers"));
		ensureHostMatchesID(followers, "followers");
		following=tryParseURL(optString(obj, "following"));
		ensureHostMatchesID(following, "following");
		if(obj.has("endpoints")){
			JsonObject endpoints=obj.getAsJsonObject("endpoints");
			sharedInbox=tryParseURL(optString(endpoints, "sharedInbox"));
			ensureHostMatchesID(sharedInbox, "sharedInbox");
		}

		if(summary!=null)
			summary=Utils.sanitizeHTML(summary);

		return this;
	}

	public abstract int getLocalID();
	public abstract URI getWallURL();
	public abstract String getTypeAndIdForURL();

	private static RSAPublicKeySpec decodeSimpleRSAKey(byte[] key) throws IOException{
		ByteArrayInputStream in=new ByteArrayInputStream(key);
		int id=in.read();
		if(id!=0x30)
			throw new IOException("Must start with SEQUENCE");
		int seqLen=readDerLength(in);
		id=in.read();
		if(id!=2)
			throw new IOException("SEQUENCE must be followed by INTEGER");
		int modLen=readDerLength(in);
		byte[] modBytes=new byte[modLen];
		in.read(modBytes);
		id=in.read();
		if(id!=2)
			throw new IOException("SEQUENCE must be followed by INTEGER");
		int expLen=readDerLength(in);
		byte[] expBytes=new byte[expLen];
		in.read(expBytes);
		return new RSAPublicKeySpec(new BigInteger(modBytes), new BigInteger(expBytes));
	}

	private static int readDerLength(InputStream in) throws IOException{
		int length=in.read();
		if((length & 0x80)!=0){
			int additionalBytes=length & 0x7F;
			if(additionalBytes>4)
				throw new IOException("Invalid length value");
			length=0;
			for(int i=0;i<additionalBytes;i++){
				length=length<<8;
				length|=in.read() & 0xFF;
			}
		}
		return Math.min(length, in.available());
	}

	protected void fillFromResultSet(ResultSet res) throws SQLException{
		byte[] key=res.getBytes("public_key");
		try{
			X509EncodedKeySpec spec=new X509EncodedKeySpec(key);
			publicKey=KeyFactory.getInstance("RSA").generatePublic(spec);
		}catch(Exception ignore){}
		key=res.getBytes("private_key");
		if(key!=null){
			try{
				PKCS8EncodedKeySpec spec=new PKCS8EncodedKeySpec(key);
				privateKey=KeyFactory.getInstance("RSA").generatePrivate(spec);
			}catch(Exception ignore){}
		}

		String _ava=res.getString("avatar");
		if(_ava!=null){
			if(_ava.startsWith("{")){
				try{
					icon=Collections.singletonList((Image)ActivityPubObject.parse(JsonParser.parseString(_ava).getAsJsonObject(), ParserContext.LOCAL));
				}catch(Exception ignore){}
			}
		}

		username=res.getString("username");
		aboutSource=res.getString("about_source");
	}

	public boolean hasWall(){
		return getWallURL()!=null;
	}

	public void ensureLocal(){
		if(StringUtils.isNotEmpty(domain))
			throw new IllegalArgumentException("Local actor is required here (got "+activityPubID+")");
	}

	public void ensureRemote(){
		if(StringUtils.isEmpty(domain))
			throw new IllegalArgumentException("Remote actor is required here (got "+activityPubID+")");
	}

	protected boolean canFollowOtherActors(){
		return true;
	}

	protected boolean canBeFollowed(){
		return true;
	}

	public String getAboutSource(){
		return StringUtils.isNotEmpty(aboutSource) ? aboutSource : summary;
	}

	public EndpointsStorageWrapper getEndpointsForStorage(){
		if(StringUtils.isEmpty(domain))
			return null;
		EndpointsStorageWrapper ep=new EndpointsStorageWrapper();
		if(followers!=null)
			ep.followers=followers.toString();
		if(following!=null)
			ep.following=following.toString();
		if(outbox!=null)
			ep.outbox=outbox.toString();
		URI wall=getWallURL();
		if(wall!=null)
			ep.wall=wall.toString();
		return ep;
	}

	public String serializeEndpoints(){
		EndpointsStorageWrapper endpoints=getEndpointsForStorage();
		return endpoints!=null ? Utils.gson.toJson(endpoints) : null;
	}

	public static class EndpointsStorageWrapper{
		@SerializedName("fs")
		public String followers;
		@SerializedName("fg")
		public String following;
		@SerializedName("ob")
		public String outbox;
		@SerializedName("wl")
		public String wall;
		@SerializedName("fr")
		public String friends;
		@SerializedName("gr")
		public String groups;
		@SerializedName("at")
		public String actorToken;
	}
}
