package smithereen.activitypub.objects;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

import smithereen.Config;
import smithereen.activitypub.ContextCollector;
import smithereen.activitypub.ParserContext;
import smithereen.data.PhotoSize;

public class LocalImage extends Image{
	public ArrayList<PhotoSize> sizes=new ArrayList<>();
	public String path;

	@Override
	protected ActivityPubObject parseActivityPubObject(JSONObject obj, ParserContext parserContext) throws Exception{
		super.parseActivityPubObject(obj, parserContext);
		localID=obj.getString("_lid");
		JSONArray s=obj.getJSONArray("_sz");
		String[] types=s.getString(0).split(" ");
		path=obj.optString("_p", "post_media");
		int offset=0;
		for(String t:types){
			PhotoSize.Type type=PhotoSize.Type.fromSuffix(t);
			int width=s.getInt(++offset);
			int height=s.getInt(++offset);
			String name=Config.uploadURLPath+"/"+path+"/"+localID+"_"+type.suffix();
			sizes.add(new PhotoSize(Config.localURI(name+".jpg"), width, height, type, PhotoSize.Format.JPEG));
			sizes.add(new PhotoSize(Config.localURI(name+".webp"), width, height, type, PhotoSize.Format.WEBP));
		}

		return this;
	}

	@Override
	public JSONObject asActivityPubObject(JSONObject obj, ContextCollector contextCollector){
		obj=super.asActivityPubObject(obj, contextCollector);

		PhotoSize biggest=null;
		int biggestArea=0;
		for(PhotoSize s:sizes){
			if(s.format!=PhotoSize.Format.JPEG)
				continue;
			if(s.type==PhotoSize.Type.RECT_LARGE || s.type==PhotoSize.Type.RECT_XLARGE)
				continue;
			int area=s.width*s.height;
			if(area>biggestArea){
				biggestArea=area;
				biggest=s;
			}
		}
		if(biggest!=null){
			obj.put("url", biggest.src.toString());
			obj.put("width", biggest.width);
			obj.put("height", biggest.height);
		}

		return obj;
	}
}
