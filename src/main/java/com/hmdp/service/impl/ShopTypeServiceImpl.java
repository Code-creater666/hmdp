package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ShopType> queryType() {
        //1.查询缓存
        String shopListJsonStr = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_LIST);
        //2.查到了返回
        if (StrUtil.isNotBlank(shopListJsonStr)){
            List<ShopType> shopTypeList = JSONUtil.toList(shopListJsonStr, ShopType.class);
            return shopTypeList;
        }
        //3.查不到查数据库
        List<ShopType> shoplist = query().orderByAsc("sort").list();
        //4.数据库查不到报错
        if (shoplist==null){
            return null;
        }
        //5.数据库查到了保存到Redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_LIST, JSONUtil.toJsonStr(shoplist));
        //6.返回
        return shoplist;
    }
}
