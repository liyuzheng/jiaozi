<?php
namespace App\Http\Controllers;

use Illuminate\Http\Request;
use Illuminate\Http\Response;
use DeviceDetector\DeviceDetector;
use DeviceDetector\Parser\Device\DeviceParserAbstract;
use App\Extensions\DeviceDetectorRedisCache;
use App\Extensions\ElasticClient;

class CollectionController extends Controller
{

    const GIF_ONE_PIXEL = 'R0lGODlhAQABAJAAAP8AAAAAACH5BAUQAAAALAAAAAABAAEAAAICBAEAOw';

    const COOKIE_KEY = '_jiaozi_uid';

    public function pageview(Request $requset)
    {
        if (! ($data = $this->getCommonData($requset))) {
            return $this->returnFailed();
        }
        $data['referer'] = $requset->query('referer', '');
        
        $data['url'] = $requset->query('url', null);
        if (is_null($data['url'])) {
            $data['url'] = $requset->header('referer', '');
        }
        // 有utm_source来源标示的
        if ($requset->has('utm_source')) {
            $data['utm_source'] = $requset->query('utm_source');
            $data['utm_medium'] = $requset->query('utm_medium', null);
            $data['utm_term'] = $requset->query('utm_term', null);
            $data['utm_content'] = $requset->query('utm_content', null);
            $data['utm_campaign'] = $requset->query('utm_campaign', null);
            
            // referer为空，代表是direct直接来源
        } else 
            if (empty($data['referer'])) {
                $data['utm_source'] = 'direct';
                
                // 判断referer和url是否是同一个host，不是则代表从其他地方跳转过来的
            } else {
                $r = parse_url($data['referer']);
                $l = parse_url($data['url']);
                if (isset($l['host']) && $r['host'] !== $l['host']) {
                    $data['utm_source'] = $data['referer'];
                }
            }
        ElasticClient::getInstance()->savePageview($data);
        
        return $this->returnImage();
    }

    public function ecommerce(Request $request)
    {
        return $this->returnImage();
    }

    public function event(Request $request)
    {
        if (! ($data = $this->getCommonData($request))) {
            return $this->returnFailed();
        }
        $data = array_merge([
            'category' => $request->query('category', ''),
            'action' => $request->query('action', ''),
            'label' => $request->query('label', ''),
            'value' => $request->query('value', ''),
            'value_number' => $request->query('value_number', null)
        ], $data);
        ElasticClient::getInstance()->saveEvent($data);
        return $this->returnImage();
    }

    /**
     * 每个数据采集，都会有公共信息的
     * 信息如下return：
     *
     * uuid 浏览器里面的cookie区分唯一浏览器或者手机IMEI
     * ip IP
     * timestamp 发送统计信息的设备IP
     * user_agent 发送统计信息的设备信息
     * os 发送统计信息的操作系统
     * os_version 操作系统版本
     * device Desktop ipad
     * client_type browser mobile app
     * client_name Chrome Facebook Stylewe-IOS
     * client_version 客户端版本
     * 
     * @param Request $request            
     */
    protected function getCommonData(Request $request)
    {
        $uuid = $request->cookies->get(self::COOKIE_KEY, null);
        if (is_null($uuid)) {
            $uuid = $request->query(self::COOKIE_KEY, null);
        }
        if (is_null($uuid)) {
            return false;
        }
        $userAgent = $request->header('User-Agent');
        $ua = $this->parseUserAgent($userAgent);
        return array_merge($ua, [
            'uuid' => $uuid,
            'ip' => $request->getClientIp(),
            'timestamp' => time(),
            'user_agent' => $userAgent
        ]);
    }

    protected function returnImage()
    {
        $response = new Response();
        $response->header('Content-Type', 'image/gif')->setContent(base64_decode(self::GIF_ONE_PIXEL));
        return $response;
    }

    protected function returnFailed()
    {
        $response = new Response();
        $response->setStatusCode(Response::HTTP_BAD_REQUEST);
        return $response;
    }

    /**
     * 分析user-agent
     * 
     * @param unknown $userAgent            
     * @return unknown[]|string[]
     */
    protected function parseUserAgent($userAgent)
    {
        /*
         * Custom User-Agent:
         * StyleWeShopping/1.2.3 Android/5.1.0 (GALAXY S5)
         * StyleWeShopping/1.2.3 iOS/9.3.4 (iPhone 6s)
         */
        if (preg_match('/StyleWeShopping\/([0-9\.]+) (Android|iOS)\/([0-9\.]+) \((.*)\)/', $userAgent, $matches)) {
            return [
                'os' => $matches[2],
                'os_version' => $matches[3],
                'device' => $matches[4],
                'client_type' => 'mobile app',
                'client_name' => 'StyleWeShopping',
                'client_version' => $matches[1]
            ];
        }
        
        // set version style x.y.z
        DeviceParserAbstract::setVersionTruncation(DeviceParserAbstract::VERSION_TRUNCATION_PATCH);
        
        $dd = new DeviceDetector($userAgent);
        $dd->setCache(new DeviceDetectorRedisCache());
        $dd->discardBotInformation();
        $dd->skipBotDetection();
        
        $dd->parse();
        
        if (! $dd->isBot()) {
            $client = $dd->getClient();
            $os = $dd->getOs();
            $data = [
                'os' => isset($os['name']) ? $os['name'] : 'other',
                'os_version' => isset($os['version']) ? $os['version'] : '0.0.0',
                'device' => $dd->getModel() ?: $dd->getDeviceName(),
                'client_type' => $client['type'],
                'client_name' => $client['name'],
                'client_version' => $client['version']
            ];
            return $data;
        }
        return [];
    }
}