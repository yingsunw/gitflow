package net.risesoft.web.controller;

import net.risesoft.config.ConfigConstants;
import net.risesoft.model.FileAttribute;
import net.risesoft.service.FileHandlerService;
import net.risesoft.service.FilePreview;
import net.risesoft.service.FilePreviewFactory;
import net.risesoft.service.cache.CacheService;
import net.risesoft.service.impl.OtherFilePreviewImpl;
import net.risesoft.utils.KkFileUtils;
import net.risesoft.utils.RandomValidateCodeUtil;
import net.risesoft.utils.WebUtils;
import fr.opensagres.xdocreport.core.io.IOUtils;
import io.mola.galimatias.GalimatiasParseException;
import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.imageio.ImageIO;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.awt.image.RenderedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * @author yudian-it
 */
@Controller
public class OnlinePreviewController {

    public static final String BASE64_DECODE_ERROR_MSG = "Base64解码失败，请检查你的 %s 是否采用 Base64 + urlEncode 双重编码了！";
    private final Logger logger = LoggerFactory.getLogger(OnlinePreviewController.class);

    private final FilePreviewFactory previewFactory;
    private final CacheService cacheService;
    private final FileHandlerService fileHandlerService;
    private final OtherFilePreviewImpl otherFilePreview;

    public OnlinePreviewController(FilePreviewFactory filePreviewFactory, FileHandlerService fileHandlerService, CacheService cacheService, OtherFilePreviewImpl otherFilePreview) {
        this.previewFactory = filePreviewFactory;
        this.fileHandlerService = fileHandlerService;
        this.cacheService = cacheService;
        this.otherFilePreview = otherFilePreview;
    }

    @GetMapping("/onlinePreview")
    public String onlinePreview(String url, Model model, HttpServletRequest req) {

        String fileUrl;
        try {
            fileUrl = WebUtils.decodeUrl(url);
        } catch (Exception ex) {
            String errorMsg = String.format(BASE64_DECODE_ERROR_MSG, "url");
            return otherFilePreview.notSupportedFile(model, errorMsg);
        }
        FileAttribute fileAttribute = fileHandlerService.getFileAttribute(fileUrl, req);
        model.addAttribute("file", fileAttribute);
        FilePreview filePreview = previewFactory.get(fileAttribute);
        logger.info("预览文件url：{}，previewType：{}", fileUrl, fileAttribute.getType());
        return filePreview.filePreviewHandle(fileUrl, model, fileAttribute);
    }

    @GetMapping("/picturesPreview")
    public String picturesPreview(String urls, Model model, HttpServletRequest req) {
        String fileUrls;
        try {
            fileUrls = WebUtils.decodeUrl(urls);
            // 防止XSS攻击
            fileUrls = KkFileUtils.htmlEscape(fileUrls);
        } catch (Exception ex) {
            String errorMsg = String.format(BASE64_DECODE_ERROR_MSG, "urls");
            return otherFilePreview.notSupportedFile(model, errorMsg);
        }
        logger.info("预览文件url：{}，urls：{}", fileUrls, urls);
        // 抽取文件并返回文件列表
        String[] images = fileUrls.split("\\|");
        List<String> imgUrls = Arrays.asList(images);
        model.addAttribute("imgUrls", imgUrls);
        String currentUrl = req.getParameter("currentUrl");
        if (StringUtils.hasText(currentUrl)) {
            String decodedCurrentUrl = new String(Base64.decodeBase64(currentUrl));
            decodedCurrentUrl = KkFileUtils.htmlEscape(decodedCurrentUrl);   // 防止XSS攻击
            model.addAttribute("currentUrl", decodedCurrentUrl);
        } else {
            model.addAttribute("currentUrl", imgUrls.get(0));
        }
        return FilePreview.PICTURE_FILE_PREVIEW_PAGE;
    }

    /**
     * 根据url获取文件内容
     * 当pdfjs读取存在跨域问题的文件时将通过此接口读取
     *
     * @param urlPath  url
     * @param response response
     */
    @GetMapping("/getCorsFile")
    public void getCorsFile(String urlPath, HttpServletResponse response) throws IOException {
        try {
            urlPath = WebUtils.decodeUrl(urlPath);
        } catch (Exception ex) {
            logger.error(String.format(BASE64_DECODE_ERROR_MSG, urlPath), ex);
            return;
        }
        HttpURLConnection urlcon = null;
        InputStream inputStream = null;
        String urlStr;
        assert urlPath != null;
        if (!urlPath.toLowerCase().startsWith("http") && !urlPath.toLowerCase().startsWith("https") && !urlPath.toLowerCase().startsWith("ftp")) {
            logger.info("读取跨域文件异常，可能存在非法访问，urlPath：{}", urlPath);
            return;
        }
        logger.info("下载跨域pdf文件url：{}", urlPath);
        if (!urlPath.toLowerCase().startsWith("ftp:")) {
            try {
                URL url = WebUtils.normalizedURL(urlPath);
                urlcon = (HttpURLConnection) url.openConnection();
                urlcon.setConnectTimeout(30000);
                urlcon.setReadTimeout(30000);
                urlcon.setInstanceFollowRedirects(false);
                int responseCode = urlcon.getResponseCode();
                if (responseCode == 403 || responseCode == 500) { //403  500
                    logger.error("读取跨域文件异常，url：{}，错误：{}", urlPath, responseCode);
                    return;
                }
                if (responseCode == HttpURLConnection.HTTP_MOVED_PERM || responseCode == HttpURLConnection.HTTP_MOVED_TEMP) { //301 302
                    url = new URL(urlcon.getHeaderField("Location"));
                    urlcon = (HttpURLConnection) url.openConnection();
                }
                if (responseCode == 404) {  //404
                    try {
                        urlStr = URLDecoder.decode(urlPath, StandardCharsets.UTF_8.name());
                        urlStr = URLDecoder.decode(urlStr, StandardCharsets.UTF_8.name());
                        url = WebUtils.normalizedURL(urlStr);
                        urlcon = (HttpURLConnection) url.openConnection();
                        urlcon.setConnectTimeout(30000);
                        urlcon.setReadTimeout(30000);
                        urlcon.setInstanceFollowRedirects(false);
                        responseCode = urlcon.getResponseCode();
                        if (responseCode == HttpURLConnection.HTTP_MOVED_PERM || responseCode == HttpURLConnection.HTTP_MOVED_TEMP) { //301 302
                            url = new URL(urlcon.getHeaderField("Location"));
                        }
                        if (responseCode == 404 || responseCode == 403 || responseCode == 500) {
                            logger.error("读取跨域文件异常，url：{}，错误：{}", urlPath, responseCode);
                            return;
                        }
                    } catch (UnsupportedEncodingException e) {
                        e.printStackTrace();
                    } finally {
                        assert urlcon != null;
                        urlcon.disconnect();
                    }
                }
                if (urlPath.contains(".svg")) {
                    response.setContentType("image/svg+xml");
                }
                inputStream = (url).openStream();
                IOUtils.copy(inputStream, response.getOutputStream());

            } catch (IOException | GalimatiasParseException e) {
                logger.error("读取跨域文件异常，url：{}", urlPath);
            } finally {
                assert urlcon != null;
                urlcon.disconnect();
                IOUtils.closeQuietly(inputStream);
            }
        } else {
            try {
                URL url = WebUtils.normalizedURL(urlPath);
                if (urlPath.contains(".svg")) {
                    response.setContentType("image/svg+xml");
                }
                inputStream = (url).openStream();
                IOUtils.copy(inputStream, response.getOutputStream());
            } catch (IOException | GalimatiasParseException e) {
                logger.error("读取跨域文件异常，url：{}", urlPath);
            } finally {
                IOUtils.closeQuietly(inputStream);
            }
        }
    }

    /**
     * 验证码方法
     */
    @RequestMapping("/captcha")
    public void captcha(HttpServletRequest request, HttpServletResponse response) throws Exception {
        if (!ConfigConstants.getDeleteCaptcha()) {
            return;
        }
        response.setContentType("image/gif");
        response.setHeader("Pragma", "No-cache");
        response.setHeader("Cache-Control", "no-cache");
        response.setDateHeader("Expires", 0);
        Date date = new Date();   // 当前时间
        SimpleDateFormat formater = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");   // 设置时间格式
        String sessionCode;
        try {
            sessionCode = request.getSession().getAttribute("code").toString();  //获取已经保存的验证码
        } catch (Exception e) {
            sessionCode = null;
        }
        Object time = request.getSession().getAttribute("time");  //获取已经保存的时间
        if (ObjectUtils.isEmpty(time)) {  //判断时间是否为空
            request.getSession().setAttribute("time", formater.format(date));  //为空重新添加缓存时间
            time = request.getSession().getAttribute("time");
        }
        Date joinTime = formater.parse(String.valueOf(time));
        String dateStart = formater.format(joinTime);
        Date d1 = formater.parse(dateStart);
        // 时间差：
        long diff = date.getTime() - d1.getTime();
        long diffSeconds = diff / 1000 % 60;
        String ip = request.getRemoteAddr();
        ServletOutputStream sos = null;
        if (ObjectUtils.isEmpty(sessionCode) || diffSeconds > 50) {   //判断验证码是否为空 为空重新生成  判断是否在有效时间内 默认50秒
            Map<String, Object> codeMap = RandomValidateCodeUtil.generateCodeAndPic(ip, sessionCode, 0);
            // 验证码存入session
            request.getSession().setAttribute("code", codeMap.get("code").toString());
            // 时间存入session
            request.getSession().setAttribute("time", formater.format(date));
            // 禁止图像缓存。
            response.setHeader("Pragma", "no-cache");
            response.setHeader("Cache-Control", "no-cache");
            response.setDateHeader("Expires", -1);
            response.setContentType("image/jpeg");
            // 将图像输出到Servlet输出流中。
            try {
                sos = response.getOutputStream();
                ImageIO.write((RenderedImage) codeMap.get("codePic"), "jpeg", sos);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                assert sos != null;
                sos.close();
            }
        } else {
            // System.out.println("请输入你的姓名:");
            Map<String, Object> codeMap = RandomValidateCodeUtil.generateCodeAndPic(ip, sessionCode, 1);
            // 禁止图像缓存。
            response.setHeader("Pragma", "no-cache");
            response.setHeader("Cache-Control", "no-cache");
            response.setDateHeader("Expires", -1);
            response.setContentType("image/jpeg");
            // 将图像输出到Servlet输出流中。
            try {
                sos = response.getOutputStream();
                ImageIO.write((RenderedImage) codeMap.get("codePic"), "jpeg", sos);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                assert sos != null;
                sos.close();
            }

        }

    }

    /**
     * 通过api接口入队
     *
     * @param url 请编码后在入队
     */
    @GetMapping("/addTask")
    @ResponseBody
    public String addQueueTask(String url) {
        logger.info("添加转码队列url：{}", url);
        cacheService.addQueueTask(url);
        return "success";
    }
}
