package com.seckill.stock.service;

import com.seckill.stock.dao.SkuDao;
import com.seckill.stock.pojo.LimitPolicy;
import com.seckill.stock.pojo.Sku;
import com.seckill.stock.pojo.SpuDetail;
import com.seckill.stock.vo.SkuVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * @Auther: wdd
 * @Date: 2019/9/8 12:06
 * @Description:
 */
@Service
public class SkuService {

    @Autowired
    private SkuDao skuDao;
    @Autowired
    private RestTemplate restTemplate;
    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * 返回所有库存所有商品信息
     * 并且带有关于商品的是否秒杀的政策信息
     * @return
     */
    public Map<String,Object> findAll(){
        //查找的是tb_sku表，所以返回的结果中只有商品信息，没有秒杀政策信息
        List<SkuVo> skuList = skuDao.findAll();
        Map<String, Object> map = new HashMap<>();
        if(skuList == null && skuList.size() == 0){
            map.put("result",false);
            map.put("msg","查询失败");
            return map;
        }
        //获取政策信息
        map = getLimitPolicy(skuList);
        map.put("result",true);
        map.put("msg","查询成功");

        //将tb_sku表中查询出来的信息封装到map集合
        map.put("sku_list",skuList);
        return map;
    }

    public Map<String,Object> getStock(String sku_id){
        List<SkuVo> skuVoList = skuDao.getStock(sku_id); // 只有一条数据
        Map<String, Object> map = new HashMap<>();
        if(skuVoList == null && skuVoList.size() == 0){
            map.put("result",false);
            map.put("msg","查询失败");
            return map;
        }
        map = getLimitPolicy(skuVoList);
        map.put("result",true);
        map.put("msg","查询成功");
        map.put("sku",skuVoList);
        return map;
    }

    /**
     * 负责设置秒杀政策对象信息和秒杀的商品对象信息
     * 根据前端传来的商品秒杀信息，判断是否存储到redis或者数据库
     * @param limitPolicy
     * @return
     */
    @Transactional
    public Map<String,Object> insertLimitPolicy(LimitPolicy limitPolicy){
        //1、判断传入的参数是不是合法
        Map<String, Object> map = new HashMap<>();
        if(limitPolicy == null){
            map.put("result",false);
            map.put("msg","数据传入错误");
            return map;
        }
        //2、从StockDao接口中调用insertLimitPolicy方法
        int flag = skuDao.insertLimitPolicy(limitPolicy);
        //3、判断执行成功或失败，如果失败，返回错误信息
        if(flag != 1){
            map.put("result", false);
            map.put("msg", "数据执行咋又失败了");
            return map;
        }
        //4、如果成功，写入redis，需要写入有效期，key取名：LIMIT_POLICY_{sku_id}
        long diff = 0;
        //4.1获取当前时间
        String now = restTemplate.getForObject("http://localhost:8000/getTime", String.class);
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        //结束日期减去当前日期得到政策的有效期
        try {
            //4.2从limitpolicy政策中获取结束时间
            Date end_time = simpleDateFormat.parse(simpleDateFormat.format(limitPolicy.getEnd_time()));
            Date now_time = simpleDateFormat.parse(now);
            //4.3得到该秒杀政策持续时间
            diff = (end_time.getTime() - now_time.getTime())/1000;

            if (diff<0){
                map.put("result", false);
                map.put("msg", "结束时间不能小于当前时间");
                return map;
            }
        } catch (ParseException e) {
            map.put("result", false);
            map.put("msg", "日期转换又失败了");
            return map;
        }
        //4.4设置redis秒杀政策，key为LIMIT_POLICY_+商品id，value为政策对象（limitpolicy），持续时间为diff
        redisTemplate.opsForValue().set("LIMIT_POLICY_"+limitPolicy.getSku_id(),limitPolicy,diff, TimeUnit.SECONDS);
        //获取该商品信息(库存等)
        List<SkuVo> skuVoList = skuDao.getStock(limitPolicy.getSku_id().toString());
        SkuVo skuVo = skuVoList.get(0);
        //4.5设置redis秒杀政策的具体商品对象，key为LIMIT_POLICY_+商品id，skuVo为政策对象（limitpolicy），持续时间为diff
        redisTemplate.opsForValue().set("SKU_"+limitPolicy.getSku_id(),skuVo,diff, TimeUnit.SECONDS);
        //5、返回正常信息
        map.put("result", true);
        map.put("msg", "");
        return map;
    }

    /**
     * 返回所有商品，并且带有秒杀政策标识
     * @param list  所有的库存商品
     * @return
     */
    private Map<String,Object> getLimitPolicy(List<SkuVo> list){
        Map<String, Object> resultMap = new HashMap<>();

        //遍历每一个从tb_sku表中查出来的数据，封装类型是SkuVo类型
        for (SkuVo skuVo : list) {
            //3.1、从redis取出政策
            //根据商品信息去redis中判断是否有秒杀政策
            LimitPolicy limitPolicy = (LimitPolicy) redisTemplate.opsForValue().get("LIMIT_POLICY_" + skuVo.getSku_id());
            //3.2、判断有政策的才继续
            if(limitPolicy != null){
                //3.3、开始时间小于等于当前时间，并且当前时间小于等于结束时间
                SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
                String now = restTemplate.getForObject("http://localhost:8000/getTime", String.class);
                try {
                    Date end_time = simpleDateFormat.parse(simpleDateFormat.format(limitPolicy.getEnd_time()));
                    Date begin_time = simpleDateFormat.parse(simpleDateFormat.format(limitPolicy.getBegin_time()));
                    Date now_time = simpleDateFormat.parse(now);

                    //设置秒杀政策信息，只有redis中存在并且符合时间的商品才会设置
                    if (begin_time.getTime()<=now_time.getTime()&&now_time.getTime()<=end_time.getTime()){
                        skuVo.setLimitPrice(limitPolicy.getPrice());
                        skuVo.setLimitQuanty(limitPolicy.getQuanty());
                        skuVo.setLimitBeginTime(limitPolicy.getBegin_time());
                        skuVo.setLimitEndTime(limitPolicy.getEnd_time());
                        skuVo.setNowTime(now_time);
                    }
                } catch (ParseException e) {
                    e.printStackTrace();
                }
            }
        }
        resultMap.put("result", true);
        resultMap.put("msg", "");
        return resultMap;
    }

}
