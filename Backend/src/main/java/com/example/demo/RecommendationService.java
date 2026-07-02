package com.example.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;

@Service
public class RecommendationService {

    @Value("${file.upload-dir}")
    private String uploadDir;

    @Value("${ai.clip.url}")
    private String aiClipUrl; // application.properties의 http://localhost:8001/internal/clip/recommend 매핑

    @Value("${ai.mediapipe.url}")
    private String aiMediaPipeUrl; // application.properties의 http://localhost:8002/api/v1/analyze/body 매핑

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public RecommendationService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 프론트엔드의 단일 요청을 받아 로컬 백업 후,
     * CLIP 추천 -> MediaPipe 체형 분석 및 캐싱 -> Top-5 자동 가상 피팅 루프를 순차 수행하여 합성 반환합니다.
     */
    public Map<String, Object> processRecommendation(UploadRequestDto dto) throws Exception {
        
        // 0. 전처리 및 로컬 파일 백업
        backupFiles(dto);

        // ==========================================
        // STEP 1. CLIP 서버 (8001) 호출 -> 추천 의류 리스트 확보
        // ==========================================
        HttpHeaders clipHeaders = new HttpHeaders();
        clipHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> clipBody = new LinkedMultiValueMap<>();

        if (dto.getClothingImages() != null && !dto.getClothingImages().isEmpty()) {
            for (MultipartFile img : dto.getClothingImages()) {
                if (!img.isEmpty()) {
                    final String fileName = img.getOriginalFilename();
                    final byte[] bytes = img.getBytes();
                    clipBody.add("style_images", new ByteArrayResource(bytes) {
                        @Override
                        public String getFilename() { return fileName; }
                    });
                }
            }
        } else {
            throw new IllegalArgumentException("추천을 위한 스타일 이미지(clothingImages)가 누락되었습니다.");
        }

        HttpEntity<MultiValueMap<String, Object>> clipRequest = new HttpEntity<>(clipBody, clipHeaders);
        ResponseEntity<Map> clipResponse = restTemplate.postForEntity(aiClipUrl, clipRequest, Map.class);
        Map<String, Object> clipResult = clipResponse.getBody();


        // ==========================================
        // STEP 2. MediaPipe 서버 (8002) 호출 -> 체형 분석 및 전신 사진 캐싱
        // ==========================================
        String userId = "user_" + UUID.randomUUID().toString().substring(0, 8); // 폴백용 세션 ID
        double heightCm = 175.0;
        String gender = "MALE";

        if (dto.getUserInfo() != null && !dto.getUserInfo().trim().isEmpty()) {
            JsonNode jsonNode = objectMapper.readTree(dto.getUserInfo());
            if (jsonNode.has("userId")) userId = jsonNode.get("userId").asText();
            if (jsonNode.has("height")) heightCm = jsonNode.get("height").asDouble();
            if (jsonNode.has("gender")) gender = jsonNode.get("gender").asText();
        }

        HttpHeaders mpHeaders = new HttpHeaders();
        mpHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> mpBody = new LinkedMultiValueMap<>();
        
        mpBody.add("user_id", userId);
        mpBody.add("height_cm", heightCm);
        mpBody.add("gender", gender);
        
        if (dto.getFullBodyImage() == null || dto.getFullBodyImage().isEmpty()) {
            throw new IllegalArgumentException("체형 분석을 위한 전신 사진(fullBodyImage)이 누락되었습니다.");
        }
        
        final String bodyFileName = dto.getFullBodyImage().getOriginalFilename();
        final byte[] bodyBytes = dto.getFullBodyImage().getBytes();
        mpBody.add("file", new ByteArrayResource(bodyBytes) {
            @Override
            public String getFilename() { return bodyFileName; }
        });

        HttpEntity<MultiValueMap<String, Object>> mpRequest = new HttpEntity<>(mpBody, mpHeaders);
        ResponseEntity<Map> mpResponse = restTemplate.postForEntity(aiMediaPipeUrl, mpRequest, Map.class);
        Map<String, Object> mediaPipeResult = mpResponse.getBody();


        // ==========================================
        // STEP 3. [완벽 싱크] CLIP 추천 결과를 기반으로 Top-5 의류 파일명 추출
        // ==========================================
        List<Map<String, Object>> tryOnResultsList = new ArrayList<>();
        List<String> topGarments = new ArrayList<>();

        if (clipResult != null && clipResult.containsKey("recommendations")) {
            // CLIP app.py 스펙인 "recommendations" 리스트 파싱
            List<Map<String, Object>> reconList = (List<Map<String, Object>>) clipResult.get("recommendations");
            
            // 결과 이미지 중 상위 최대 5개 추출 (성능 및 대기시간 최적화)
            int limit = Math.min(reconList.size(), 5); 
            for (int i = 0; i < limit; i++) {
                Map<String, Object> item = reconList.get(i);
                if (item.containsKey("image_name")) {
                    topGarments.add((String) item.get("image_name")); // 예: "15970.jpg"
                }
            }
        } else {
            // CLIP 장애 시 가동할 폴백용 기본 이미지셋 매핑
            topGarments = Arrays.asList("15970.jpg", "12430.jpg", "10003.jpg");
        }


        // ==========================================
        // STEP 4. [자동화 루프] MediaPipe 가상 피팅(/api/v1/tryon) 동적 연속 호출
        // ==========================================
        // 기존 8002번 분석 주소를 기반으로 가상 피팅 엔드포인트 주소 생성
        String tryOnUrl = aiMediaPipeUrl.replace("/api/v1/analyze/body", "/api/v1/tryon");

        for (String garmentName : topGarments) {
            try {
                HttpHeaders tryOnHeaders = new HttpHeaders();
                tryOnHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
                MultiValueMap<String, Object> tryOnBody = new LinkedMultiValueMap<>();
                
                tryOnBody.add("user_id", userId);
                tryOnBody.add("garment_name", garmentName); // MediaPipe app.py 80번 라인 명세 일치

                HttpEntity<MultiValueMap<String, Object>> tryOnRequest = new HttpEntity<>(tryOnBody, tryOnHeaders);
                ResponseEntity<Map> tryOnResponse = restTemplate.postForEntity(tryOnUrl, tryOnRequest, Map.class);
                
                if (tryOnResponse.getStatusCode() == HttpStatus.OK && tryOnResponse.getBody() != null) {
                    Map<String, Object> resBody = tryOnResponse.getBody();
                    Map<String, Object> dataSection = (Map<String, Object>) resBody.get("data");
                    
                    Map<String, Object> tryOnItem = new HashMap<>();
                    tryOnItem.put("garment_name", garmentName);
                    tryOnItem.put("tryon_image_base64", dataSection.get("tryon_image_base64"));
                    tryOnResultsList.add(tryOnItem);
                }
            } catch (Exception e) {
                // 특정 의류 피팅 도중 크래시가 나더라도 전체 프로세스가 멈추지 않도록 예외 차단 (장애 격리)
                System.err.println("❌ 의류 [" + garmentName + "] 가상 피팅 연산 실패 (스킵): " + e.getMessage());
            }
        }


        // ==========================================
        // STEP 5. 최종 결과 바구니 조립 및 프론트엔드 일괄 리턴
        // ==========================================
        Map<String, Object> finalResult = new HashMap<>();
        finalResult.put("status", "SUCCESS");
        finalResult.put("clip_recommendations", clipResult); // 의류 추천 메타데이터 목록
        finalResult.put("body_analysis", mediaPipeResult);     // 체형 분석 및 KS 규격 추천 데이터
        finalResult.put("top5_tryon_images", tryOnResultsList); // 내 몸에 옷이 합성된 5장의 가상피팅 이미지 팩

        return finalResult;
    }

    /**
     * 로컬 스토리지 업로드 원본 백업 비즈니스 로직
     */
    private void backupFiles(UploadRequestDto dto) throws Exception {
        if (dto.getClothingImages() != null) {
            for (MultipartFile img : dto.getClothingImages()) {
                if (!img.isEmpty()) {
                    Path path = Paths.get(uploadDir + "clothing_" + img.getOriginalFilename());
                    Files.createDirectories(path.getParent());
                    Files.copy(img.getInputStream(), path, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        if (dto.getFullBodyImage() != null && !dto.getFullBodyImage().isEmpty()) {
            MultipartFile bodyFile = dto.getFullBodyImage();
            Path path = Paths.get(uploadDir + "body_" + bodyFile.getOriginalFilename());
            Files.createDirectories(path.getParent());
            Files.copy(bodyFile.getInputStream(), path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}