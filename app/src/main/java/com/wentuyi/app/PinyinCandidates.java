package com.wentuyi.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class PinyinCandidates {
    private static final int MAX_SYLLABLE_LENGTH = 6;
    private static final Map<String, String[]> CANDIDATES = createCandidates();

    private PinyinCandidates() {
    }

    static List<String> candidatesFor(String input) {
        String normalized = normalize(input);
        ArrayList<String> result = new ArrayList<>();
        if (normalized.isEmpty()) {
            return result;
        }

        String[] direct = CANDIDATES.get(normalized);
        if (direct != null) {
            for (String candidate : direct) {
                addUnique(result, candidate);
            }
        }

        String segmented = segmentToFirstCandidates(normalized);
        if (segmented != null) {
            addUnique(result, segmented);
        }

        addUnique(result, normalized);
        return result;
    }

    static String firstCandidateOrRaw(String input) {
        List<String> candidates = candidatesFor(input);
        return candidates.isEmpty() ? normalize(input) : candidates.get(0);
    }

    private static String normalize(String input) {
        StringBuilder normalized = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                normalized.append((char) (c + ('a' - 'A')));
            } else if (c >= 'a' && c <= 'z') {
                normalized.append(c);
            }
        }
        return normalized.toString();
    }

    private static String segmentToFirstCandidates(String input) {
        String[] best = new String[input.length() + 1];
        best[input.length()] = "";
        for (int start = input.length() - 1; start >= 0; start--) {
            int maxEnd = Math.min(input.length(), start + MAX_SYLLABLE_LENGTH);
            for (int end = maxEnd; end > start; end--) {
                String syllable = input.substring(start, end);
                String[] candidates = CANDIDATES.get(syllable);
                if (candidates == null || best[end] == null) {
                    continue;
                }
                best[start] = candidates[0] + best[end];
                break;
            }
        }
        return best[0];
    }

    private static void addUnique(ArrayList<String> candidates, String candidate) {
        if (!candidates.contains(candidate)) {
            candidates.add(candidate);
        }
    }

    private static Map<String, String[]> createCandidates() {
        HashMap<String, String[]> map = new HashMap<>();
        put(map, "nihao", "你好", "你号");
        put(map, "ninhao", "您好");
        put(map, "zaijian", "再见");
        put(map, "xiexie", "谢谢");
        put(map, "buyao", "不要");
        put(map, "keyi", "可以");
        put(map, "buxing", "不行");
        put(map, "meishi", "没事");
        put(map, "meiyou", "没有");
        put(map, "zhongwen", "中文");
        put(map, "yingwen", "英文");
        put(map, "qingwen", "请问");
        put(map, "wentuyi", "文图易");
        put(map, "shouji", "手机");
        put(map, "shurufa", "输入法");
        put(map, "jianpan", "键盘");
        put(map, "ceshi", "测试");
        put(map, "xinxi", "信息");
        put(map, "tupian", "图片");
        put(map, "jiami", "加密");
        put(map, "jiemi", "解密");
        put(map, "wode", "我的");
        put(map, "women", "我们");
        put(map, "nide", "你的");
        put(map, "nimen", "你们");
        put(map, "tamen", "他们");
        put(map, "jintian", "今天");
        put(map, "mingtian", "明天");
        put(map, "zuotian", "昨天");
        put(map, "xianzai", "现在");
        put(map, "dengyixia", "等一下");
        put(map, "mafan", "麻烦");
        put(map, "haode", "好的");
        put(map, "haoma", "好吗");
        put(map, "zenme", "怎么");
        put(map, "weishenme", "为什么");
        put(map, "shenme", "什么");
        put(map, "shijian", "时间");
        put(map, "difang", "地方");
        put(map, "gongzuo", "工作");
        put(map, "xuexi", "学习");
        put(map, "shenghuo", "生活");
        put(map, "zhongguo", "中国");
        put(map, "beijing", "北京");
        put(map, "shanghai", "上海");
        put(map, "guangzhou", "广州");
        put(map, "shenzhen", "深圳");
        put(map, "pingguo", "苹果");
        put(map, "anzhuo", "安卓");
        put(map, "sanxing", "三星");

        put(map, "a", "啊");
        put(map, "ai", "爱", "挨");
        put(map, "an", "安", "按");
        put(map, "ang", "昂");
        put(map, "ba", "把", "吧", "八");
        put(map, "bai", "白", "百");
        put(map, "ban", "办", "班", "半");
        put(map, "bang", "帮", "棒");
        put(map, "bao", "包", "报", "宝");
        put(map, "bei", "被", "北");
        put(map, "ben", "本");
        put(map, "bi", "比", "笔", "必");
        put(map, "bian", "边", "变");
        put(map, "biao", "表");
        put(map, "bie", "别");
        put(map, "bin", "宾");
        put(map, "bing", "并", "病");
        put(map, "bo", "波");
        put(map, "bu", "不", "部");
        put(map, "ca", "擦");
        put(map, "cai", "才", "菜");
        put(map, "can", "参", "餐");
        put(map, "cang", "藏");
        put(map, "cao", "草");
        put(map, "ce", "测");
        put(map, "ceng", "曾");
        put(map, "cha", "查", "茶");
        put(map, "chai", "拆");
        put(map, "chan", "产");
        put(map, "chang", "长", "常");
        put(map, "chao", "超");
        put(map, "che", "车");
        put(map, "chen", "陈");
        put(map, "cheng", "成", "程", "城");
        put(map, "chi", "吃");
        put(map, "chong", "重");
        put(map, "chou", "抽");
        put(map, "chu", "出", "处");
        put(map, "chuan", "传", "船");
        put(map, "chuang", "窗");
        put(map, "chui", "吹");
        put(map, "chun", "春");
        put(map, "ci", "次", "词");
        put(map, "cong", "从");
        put(map, "cu", "粗");
        put(map, "cuo", "错");
        put(map, "da", "大", "打");
        put(map, "dai", "带", "代");
        put(map, "dan", "但", "单");
        put(map, "dang", "当");
        put(map, "dao", "到", "道");
        put(map, "de", "的", "得");
        put(map, "deng", "等");
        put(map, "di", "地", "第");
        put(map, "dian", "点", "电");
        put(map, "diao", "调");
        put(map, "die", "跌");
        put(map, "ding", "定");
        put(map, "diu", "丢");
        put(map, "dong", "动", "东");
        put(map, "dou", "都");
        put(map, "du", "度", "读");
        put(map, "duan", "短", "段");
        put(map, "dui", "对");
        put(map, "dun", "顿");
        put(map, "duo", "多");
        put(map, "e", "额");
        put(map, "en", "嗯");
        put(map, "er", "二", "儿");
        put(map, "fa", "发", "法");
        put(map, "fan", "反", "饭");
        put(map, "fang", "方", "放");
        put(map, "fei", "非", "飞");
        put(map, "fen", "分");
        put(map, "feng", "风");
        put(map, "fo", "佛");
        put(map, "fou", "否");
        put(map, "fu", "复", "服", "福");
        put(map, "ga", "噶");
        put(map, "gai", "改", "该");
        put(map, "gan", "感", "干");
        put(map, "gang", "刚");
        put(map, "gao", "高", "搞");
        put(map, "ge", "个", "各");
        put(map, "gei", "给");
        put(map, "gen", "跟");
        put(map, "geng", "更");
        put(map, "gong", "工", "公");
        put(map, "gou", "够");
        put(map, "gu", "古", "故");
        put(map, "gua", "挂");
        put(map, "guai", "怪");
        put(map, "guan", "关", "管");
        put(map, "guang", "光");
        put(map, "gui", "归", "贵");
        put(map, "gun", "滚");
        put(map, "guo", "国", "过");
        put(map, "ha", "哈");
        put(map, "hai", "还", "海");
        put(map, "han", "汉", "含");
        put(map, "hang", "行", "航");
        put(map, "hao", "好", "号");
        put(map, "he", "和", "喝");
        put(map, "hei", "黑");
        put(map, "hen", "很");
        put(map, "heng", "横");
        put(map, "hong", "红");
        put(map, "hou", "后");
        put(map, "hu", "护", "胡");
        put(map, "hua", "话", "花");
        put(map, "huai", "坏");
        put(map, "huan", "换", "还");
        put(map, "huang", "黄");
        put(map, "hui", "会", "回");
        put(map, "hun", "混");
        put(map, "huo", "或", "活");
        put(map, "ji", "机", "几", "及");
        put(map, "jia", "家", "加");
        put(map, "jian", "件", "见", "间");
        put(map, "jiang", "将", "讲");
        put(map, "jiao", "叫", "教");
        put(map, "jie", "解", "接");
        put(map, "jin", "进", "今");
        put(map, "jing", "经", "京");
        put(map, "jiong", "窘");
        put(map, "jiu", "就", "九");
        put(map, "ju", "局", "句");
        put(map, "jue", "觉", "决");
        put(map, "jun", "军");
        put(map, "ka", "卡");
        put(map, "kai", "开");
        put(map, "kan", "看");
        put(map, "kang", "康");
        put(map, "kao", "考");
        put(map, "ke", "可", "课");
        put(map, "ken", "肯");
        put(map, "keng", "坑");
        put(map, "kong", "空");
        put(map, "kou", "口");
        put(map, "ku", "苦");
        put(map, "kua", "跨");
        put(map, "kuai", "快");
        put(map, "kuan", "款");
        put(map, "kuang", "狂");
        put(map, "kui", "亏");
        put(map, "kun", "困");
        put(map, "kuo", "扩");
        put(map, "la", "拉");
        put(map, "lai", "来");
        put(map, "lan", "蓝");
        put(map, "lang", "浪");
        put(map, "lao", "老");
        put(map, "le", "了", "乐");
        put(map, "lei", "类");
        put(map, "leng", "冷");
        put(map, "li", "里", "理", "力");
        put(map, "lia", "俩");
        put(map, "lian", "联", "连");
        put(map, "liang", "两", "量");
        put(map, "liao", "了", "料");
        put(map, "lie", "列");
        put(map, "lin", "林");
        put(map, "ling", "令", "另");
        put(map, "liu", "六", "流");
        put(map, "long", "龙");
        put(map, "lou", "楼");
        put(map, "lu", "路", "录");
        put(map, "luan", "乱");
        put(map, "lun", "论");
        put(map, "luo", "落", "罗");
        put(map, "lv", "绿", "旅");
        put(map, "ma", "吗", "嘛");
        put(map, "mai", "买", "卖");
        put(map, "man", "慢", "满");
        put(map, "mang", "忙");
        put(map, "mao", "毛");
        put(map, "me", "么");
        put(map, "mei", "没", "美");
        put(map, "men", "们", "门");
        put(map, "meng", "梦");
        put(map, "mi", "密", "米");
        put(map, "mian", "面", "免");
        put(map, "miao", "秒");
        put(map, "mie", "灭");
        put(map, "min", "民");
        put(map, "ming", "明", "名");
        put(map, "miu", "谬");
        put(map, "mo", "末", "摸");
        put(map, "mou", "某");
        put(map, "mu", "目", "木");
        put(map, "na", "那", "拿");
        put(map, "nai", "奶");
        put(map, "nan", "难", "男");
        put(map, "nang", "囊");
        put(map, "nao", "闹");
        put(map, "ne", "呢");
        put(map, "nei", "内");
        put(map, "nen", "嫩");
        put(map, "neng", "能");
        put(map, "ni", "你", "呢");
        put(map, "nian", "年", "念");
        put(map, "niang", "娘");
        put(map, "niao", "鸟");
        put(map, "nie", "捏");
        put(map, "nin", "您");
        put(map, "ning", "宁");
        put(map, "niu", "牛");
        put(map, "nong", "农");
        put(map, "nu", "怒");
        put(map, "nuan", "暖");
        put(map, "nuo", "诺");
        put(map, "nv", "女");
        put(map, "o", "哦");
        put(map, "ou", "欧");
        put(map, "pa", "怕", "爬");
        put(map, "pai", "排", "拍");
        put(map, "pan", "盘");
        put(map, "pang", "旁");
        put(map, "pao", "跑");
        put(map, "pei", "配", "陪");
        put(map, "pen", "盆");
        put(map, "peng", "朋");
        put(map, "pi", "批", "皮");
        put(map, "pian", "片", "篇");
        put(map, "piao", "票");
        put(map, "pie", "撇");
        put(map, "pin", "品");
        put(map, "ping", "平", "屏");
        put(map, "po", "破");
        put(map, "pou", "剖");
        put(map, "pu", "普", "铺");
        put(map, "qi", "起", "其");
        put(map, "qia", "恰");
        put(map, "qian", "前", "钱");
        put(map, "qiang", "强");
        put(map, "qiao", "桥", "巧");
        put(map, "qie", "切");
        put(map, "qin", "请", "亲");
        put(map, "qing", "请", "清");
        put(map, "qiong", "穷");
        put(map, "qiu", "求");
        put(map, "qu", "去", "区");
        put(map, "quan", "全");
        put(map, "que", "确", "却");
        put(map, "qun", "群");
        put(map, "ran", "然");
        put(map, "rang", "让");
        put(map, "rao", "绕");
        put(map, "re", "热");
        put(map, "ren", "人", "认");
        put(map, "reng", "扔");
        put(map, "ri", "日");
        put(map, "rong", "容");
        put(map, "rou", "肉");
        put(map, "ru", "如", "入");
        put(map, "ruan", "软");
        put(map, "rui", "瑞");
        put(map, "run", "润");
        put(map, "ruo", "若");
        put(map, "sa", "撒");
        put(map, "sai", "赛");
        put(map, "san", "三");
        put(map, "sang", "桑");
        put(map, "sao", "扫");
        put(map, "se", "色");
        put(map, "sen", "森");
        put(map, "seng", "僧");
        put(map, "sha", "啥", "沙");
        put(map, "shai", "晒");
        put(map, "shan", "山", "删");
        put(map, "shang", "上");
        put(map, "shao", "少");
        put(map, "she", "设");
        put(map, "shen", "什", "神");
        put(map, "sheng", "生", "声");
        put(map, "shi", "是", "时", "事");
        put(map, "shou", "手", "收");
        put(map, "shu", "书", "输");
        put(map, "shua", "刷");
        put(map, "shuai", "帅");
        put(map, "shuan", "栓");
        put(map, "shuang", "双");
        put(map, "shui", "水");
        put(map, "shun", "顺");
        put(map, "shuo", "说");
        put(map, "si", "四", "思");
        put(map, "song", "送");
        put(map, "sou", "搜");
        put(map, "su", "速", "苏");
        put(map, "suan", "算");
        put(map, "sui", "随");
        put(map, "sun", "孙");
        put(map, "suo", "所");
        put(map, "ta", "他", "她", "它");
        put(map, "tai", "太", "台");
        put(map, "tan", "谈");
        put(map, "tang", "堂", "唐");
        put(map, "tao", "套");
        put(map, "te", "特");
        put(map, "teng", "疼");
        put(map, "ti", "题", "体");
        put(map, "tian", "天");
        put(map, "tiao", "条");
        put(map, "tie", "铁");
        put(map, "ting", "听", "停");
        put(map, "tong", "同", "通");
        put(map, "tou", "头");
        put(map, "tu", "图", "土");
        put(map, "tuan", "团");
        put(map, "tui", "推", "退");
        put(map, "tun", "吞");
        put(map, "tuo", "托");
        put(map, "wa", "哇");
        put(map, "wai", "外");
        put(map, "wan", "完", "晚");
        put(map, "wang", "网", "王");
        put(map, "wei", "为", "位");
        put(map, "wen", "文", "问");
        put(map, "weng", "翁");
        put(map, "wo", "我");
        put(map, "wu", "无", "五");
        put(map, "xi", "西", "喜");
        put(map, "xia", "下");
        put(map, "xian", "先", "线");
        put(map, "xiang", "想", "像");
        put(map, "xiao", "小", "笑");
        put(map, "xie", "写", "谢");
        put(map, "xin", "新", "心");
        put(map, "xing", "行", "型");
        put(map, "xiong", "雄");
        put(map, "xiu", "修");
        put(map, "xu", "需", "许");
        put(map, "xuan", "选");
        put(map, "xue", "学");
        put(map, "xun", "讯", "寻");
        put(map, "ya", "呀");
        put(map, "yan", "言", "眼");
        put(map, "yang", "样", "养");
        put(map, "yao", "要");
        put(map, "ye", "也", "页");
        put(map, "yi", "一", "以");
        put(map, "yin", "因", "音");
        put(map, "ying", "英", "应");
        put(map, "yong", "用");
        put(map, "you", "有", "又");
        put(map, "yu", "语", "于");
        put(map, "yuan", "元", "原");
        put(map, "yue", "月");
        put(map, "yun", "云", "运");
        put(map, "za", "咋");
        put(map, "zai", "在", "再");
        put(map, "zan", "咱");
        put(map, "zang", "脏");
        put(map, "zao", "早");
        put(map, "ze", "则");
        put(map, "zei", "贼");
        put(map, "zen", "怎");
        put(map, "zeng", "增");
        put(map, "zha", "找", "炸");
        put(map, "zhai", "摘");
        put(map, "zhan", "站", "占");
        put(map, "zhang", "张", "长");
        put(map, "zhao", "找", "照");
        put(map, "zhe", "这", "着");
        put(map, "zhen", "真");
        put(map, "zheng", "正");
        put(map, "zhi", "之", "只");
        put(map, "zhong", "中", "种");
        put(map, "zhou", "周");
        put(map, "zhu", "主", "住");
        put(map, "zhua", "抓");
        put(map, "zhuai", "拽");
        put(map, "zhuan", "转", "专");
        put(map, "zhuang", "装");
        put(map, "zhui", "追");
        put(map, "zhun", "准");
        put(map, "zhuo", "桌");
        put(map, "zi", "字", "自");
        put(map, "zong", "总");
        put(map, "zou", "走");
        put(map, "zu", "组");
        put(map, "zuan", "钻");
        put(map, "zui", "最");
        put(map, "zun", "尊");
        put(map, "zuo", "做", "左");
        return map;
    }

    private static void put(Map<String, String[]> map, String pinyin, String... candidates) {
        map.put(pinyin, candidates);
    }
}
