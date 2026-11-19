package app.cloudvoyage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import static app.cloudvoyage.Flight.Airport;

final class Airports {
    private static Airport a(String code, String city, double lat, double lon) {
        return new Airport(code, city, lat, lon);
    }

    static final List<Airport> ALL = Arrays.asList(
            a("HKG","香港",22.312,113.915), a("SZX","深圳",22.639,113.811),
            a("MFM","澳门",22.149,113.592), a("ZUH","珠海",22.006,113.376),
            a("CAN","广州",23.392,113.299), a("SWA","揭阳",23.552,116.503),
            a("XMN","厦门",24.544,118.128), a("FOC","福州",25.935,119.663),
            a("NGB","宁波",29.826,121.462), a("HGH","杭州",30.229,120.434),
            a("PVG","上海",31.144,121.808), a("SHA","上海虹桥",31.198,121.336),
            a("NKG","南京",31.742,118.862), a("TAO","青岛",36.363,120.088),
            a("PEK","北京",40.080,116.585), a("PKX","北京大兴",39.510,116.411),
            a("TSN","天津",39.124,117.346), a("SHE","沈阳",41.640,123.483),
            a("HRB","哈尔滨",45.623,126.250), a("DLC","大连",38.966,121.538),
            a("CGO","郑州",34.519,113.841), a("WUH","武汉",30.784,114.208),
            a("CSX","长沙",28.190,113.219), a("KHN","南昌",28.865,115.900),
            a("CKG","重庆",29.720,106.642), a("CTU","成都",30.578,103.947),
            a("TFU","成都天府",30.312,104.445), a("KWE","贵阳",26.539,106.801),
            a("KMG","昆明",25.101,102.929), a("NNG","南宁",22.608,108.172),
            a("KWL","桂林",25.219,110.040), a("HAK","海口",19.934,110.459),
            a("SYX","三亚",18.302,109.412), a("XIY","西安",34.447,108.752),
            a("LHW","兰州",36.515,103.620), a("URC","乌鲁木齐",43.907,87.474),
            a("LXA","拉萨",29.298,90.912), a("KHH","高雄",22.577,120.350),
            a("TPE","台北",25.080,121.232), a("MNL","马尼拉",14.509,121.020),
            a("HAN","河内",21.221,105.807), a("SGN","胡志明市",10.818,106.652),
            a("BKK","曼谷",13.693,100.750), a("SIN","新加坡",1.364,103.991),
            a("KUL","吉隆坡",2.746,101.710), a("ICN","首尔",37.460,126.441),
            a("PUS","釜山",35.179,128.938), a("HND","东京",35.549,139.780),
            a("KIX","大阪",34.434,135.244), a("FUK","福冈",33.586,130.451),
            a("CTS","札幌",42.775,141.692), a("OKA","冲绳",26.196,127.646),
            a("LHR","伦敦",51.470,-0.454), a("CDG","巴黎",49.010,2.548),
            a("AMS","阿姆斯特丹",52.308,4.764), a("FRA","法兰克福",50.037,8.562),
            a("MAD","马德里",40.472,-3.561), a("FCO","罗马",41.800,12.238),
            a("JFK","纽约",40.641,-73.778), a("BOS","波士顿",42.365,-71.010),
            a("SFO","旧金山",37.621,-122.379), a("LAX","洛杉矶",33.942,-118.408),
            a("YVR","温哥华",49.195,-123.178), a("SYD","悉尼",-33.946,151.177),
            a("MEL","墨尔本",-37.673,144.843), a("AKL","奥克兰",-37.009,174.792));

    static Airport find(String code) {
        for (Airport airport : ALL) if (airport.code.equals(code)) return airport;
        return ALL.get(0);
    }

    static List<Airport> recommend(Airport origin, int minutes) {
        double estimate = minutes / 60.0 * Flight.SPEED;
        List<Airport> sorted = new ArrayList<>(ALL);
        sorted.remove(origin);
        sorted.sort(Comparator.comparingDouble(airport -> Math.abs(Airport.distance(origin, airport) - estimate)));
        return sorted;
    }
}
