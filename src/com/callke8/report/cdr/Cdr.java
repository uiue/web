package com.callke8.report.cdr;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.callke8.utils.ArrayUtils;
import com.callke8.utils.BlankUtils;
import com.callke8.utils.DateFormatUtils;
import com.callke8.utils.MemoryVariableUtil;
import com.callke8.utils.StringUtil;
import com.jfinal.kit.PathKit;
import com.jfinal.plugin.activerecord.Db;
import com.jfinal.plugin.activerecord.Model;
import com.jfinal.plugin.activerecord.Page;
import com.jfinal.plugin.activerecord.Record;

public class Cdr extends Model<Cdr> {
	
	public static Cdr dao = new Cdr();
	
	
	public Page<Record> getCdrByPaginate(int currentPage,int numPerPage,String src,String dst,String seq,String startTime,String endTime) {
		StringBuilder sb = new StringBuilder();
		Object[] pars = new Object[5];
		int index = 0;
		
		sb.append("from cdr where 1=1 and lastapp='Dial'");
		
		if(!BlankUtils.isBlank(src)) {
			sb.append(" and src like ?");
			pars[index] = "%" + src + "%";
			index++;
		}
		
		if(!BlankUtils.isBlank(dst)) {
			sb.append(" and dst like ?");
			pars[index] = "%" + dst + "%";
			index++;
		}
		
		//序列号是否为空,序列号为空时，再判断时间条件，如果序列号不为空时，则不需要再判断时间条件
		if(!BlankUtils.isBlank(seq)) {
			Date createDate = DateFormatUtils.parseDateTime(seq, "yyyyMMddHHmmss");
			String createCallDate = DateFormatUtils.formatDateTime(createDate, "yyyy-MM-dd HH:mm:ss");
			
			if(!BlankUtils.isBlank(createCallDate)) {
				sb.append(" and calldate=?");
				pars[index] = createCallDate;
				index++;
			}
		}else {
			if(!BlankUtils.isBlank(startTime)) {
				sb.append(" and calldate>=?");
				pars[index] = startTime + " 00:00:00";
				index++;
			}
			
			if(!BlankUtils.isBlank(endTime)) {
				sb.append(" and calldate<=?");
				pars[index] = endTime + " 23:59:59";
				index++;
			}
		}
		/**
		 * 由于不同的Elastix的版本，对应的mysql的cdr的结构是不同的：
		 * 1.8版本：没有recordingfile字段，要获取录音文件里，需要从userfield中根据 audio：xxxxxxx.wav中取录音文件
		 * 2.5版本: 有 recordingfile字段，可以直接从这个字段中取出录音文件
		 */
		String version = MemoryVariableUtil.getDictName("ELASTIX_VERSION", "version");   //取得版本
		Page<Record> page = null;
		if(!BlankUtils.isBlank(version) && version.equalsIgnoreCase("1.8")) {            //1.8版本
			page = Db.paginate(currentPage, numPerPage, "select *", sb.toString() + " and userfield<>'' and disposition='ANSWERED' ORDER BY calldate DESC", ArrayUtils.copyArray(index, pars));
		}else {
			page = Db.paginate(currentPage, numPerPage, "select *", sb.toString() + " and disposition='ANSWERED' ORDER BY calldate DESC", ArrayUtils.copyArray(index, pars));
		}
		
		return page;
	}
	
	public Map getCdrByPaginateToMap(int currentPage,int numPerPage,String src,String dst,String seq,String startTime,String endTime) {
		Page<Record> page = getCdrByPaginate(currentPage, numPerPage, src, dst,seq,startTime, endTime);
		int total = page.getTotalRow();
		Map m = new HashMap();
		
		//先将list取出，并赋值路径，主要是用于前端试听录音使用
		//同时，还需要将客户的号码前缀去除
		List<Record> list = page.getList();
		List<Record> newList = new ArrayList<Record>();    //定义一个新的 list
		for(Record r:list) {
			
			String calldate = r.get("calldate").toString();          //通话时间
			/**
			 * 由于不同的Elastix的版本，对应的mysql的cdr的结构是不同的：
			 * 1.8版本：没有recordingfile字段，要获取录音文件里，需要从userfield中根据 audio：xxxxxxx.wav中取录音文件
			 * 2.5版本: 有 recordingfile字段，可以直接从这个字段中取出录音文件
			 */
			String version = MemoryVariableUtil.getDictName("ELASTIX_VERSION", "version");   //取得版本
			if(!BlankUtils.isBlank(version) && version.equalsIgnoreCase("1.8")) {            //1.8版本
				//先将userfield取出，一般如果是1.8版本的，取出的userfield的结果为audio:20141024-200016-1414152016.2475.wav
				String userField = r.get("userfield");   
				if(!BlankUtils.isBlank(userField)&&StringUtil.containsAny(userField, "audio")) {   //如果不为空，且包含audio字符串时
					String[] ufs = userField.split(":");   //将其分解，取出下标为1的，即为录音文件名
					if(ufs.length==2) { 
						r.set("recordingfile",ufs[1]);     //设置录音文件的名字
					}
				}
			}
			String recordingfile = r.get("recordingfile");                //录音文件
			String path = null;    //路径位置
			
			/**
			 * 由于不同的Elastix的版本，录音文件的位置也有差异
			 * 1.8版本：所有的录音文件都放在同一个目录下，即/var/spool/asterisk/monitor/exten　下
			 * 2.5版本: 所有的录音文件都放在日期形式的目录下，即是/var/spool/asterisk/monitor/yyyy/mm/dd  这样的形式下
			 */
			if(!BlankUtils.isBlank(version) && version.equalsIgnoreCase("1.8")) {            //1.8版本
				path = StringUtil.getPathByCallDate(null);    	  //否则直接以voices作为录音的路径
			}else {
				path = StringUtil.getPathByCallDate(calldate);    //根据通话时间分析出录音的路径
			}
			
			r.set("path", path);    //设置其路径
			//接下来，先判断当前录音文件是否为空，如果非空时，还需要判断录音文件是否真实存在
			if(!BlankUtils.isBlank(recordingfile)) {
				File file = new File(( PathKit.getWebRootPath() + "/" + path + recordingfile));
				
				if(!file.exists()) {    //如果文件不存在时，就需要将recordingfile　设置为空  
					System.out.println("文件:" + file.getPath() + " 不存在，不显示播放和下载按钮..." + file.exists());
					r.set("recordingfile",null);
				}
			}
			r.set("seq","seq:" + DateFormatUtils.formatDateTime(DateFormatUtils.parseDateTime(calldate), "yyyyMMddHHmmss"));
			newList.add(r);
		}
		m.put("total", total);
		m.put("rows",newList);
		return m;
	}
	
	
}
