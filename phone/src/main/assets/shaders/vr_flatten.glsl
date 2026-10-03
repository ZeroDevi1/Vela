__LOOK_PARAMS__//!HOOK MAIN
//!BIND HOOKED
//!DESC VR flatten to rectilinear 2D
//!WIDTH OUTPUT.w
//!HEIGHT OUTPUT.h

__LOOK_DEFINES__
#define ID_FOV __ID_FOV__
#define PROJ_MODE __PROJ_MODE__
#define STEREO_MODE __STEREO_MODE__

#define PI 3.14159265359

vec2 stereoUv(vec2 uv) {
    if (STEREO_MODE > 1.5) {
        uv.y = uv.y * 0.5;
    } else if (STEREO_MODE > 0.5) {
        uv.x = uv.x * 0.5;
    }
    return uv;
}

vec2 dirToEquirect(vec3 dir, float srcFov) {
    float lon = atan(dir.x, dir.z);
    float lat = asin(clamp(dir.y, -1.0, 1.0));
    float halfLon = radians(srcFov) * 0.5;
    return vec2(lon / (2.0 * halfLon) + 0.5, lat / PI + 0.5);
}

vec2 dirToFisheye(vec3 dir, float srcFov) {
    float theta = acos(clamp(dir.z, -1.0, 1.0));
    float r = theta / radians(srcFov);
    float mag = length(dir.xy);
    vec2 n = mag > 1e-6 ? dir.xy / mag : vec2(0.0);
    return n * r * 0.5 + 0.5;
}

vec4 hook() {
    vec2 ndc = HOOKED_pos * 2.0 - 1.0;
    float aspect = target_size.x / max(target_size.y, 1.0);
    ndc.x *= aspect;
    // D_FOV 是画面长边方向的视场角（与头显 FOV 的习惯一致）；按竖直方向算会让宽屏横向视场过大、主体显得很远。
    float tanHalf = tan(radians(D_FOV) * 0.5) / max(aspect, 1.0);
    vec3 dir = normalize(vec3(ndc.x * tanHalf, ndc.y * tanHalf, 1.0));

    float cy = cos(radians(YAW));
    float sy = sin(radians(YAW));
    float cp = cos(radians(PITCH));
    float sp = sin(radians(PITCH));
    vec3 pitched = vec3(dir.x, dir.y * cp - dir.z * sp, dir.y * sp + dir.z * cp);
    vec3 look = vec3(pitched.x * cy + pitched.z * sy, pitched.y, -pitched.x * sy + pitched.z * cy);

    vec2 uv;
    if (PROJ_MODE > 1.5) {
        uv = dirToFisheye(look, ID_FOV);
    } else {
        float srcFov = PROJ_MODE > 0.5 ? 360.0 : max(ID_FOV, 1.0);
        uv = dirToEquirect(look, srcFov);
    }

    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
        return vec4(0.0, 0.0, 0.0, 1.0);
    }
    // 不用 HOOKED_tex：Adreno 预处理器不会把 HOOKED_tex -> MAINPRESUB_tex(pos) 这种
    // "对象宏指向函数宏"继续展开，整个 hook 编译失败并被 libplacebo 禁用。
    return textureLod(HOOKED_raw, stereoUv(uv), 0.0) * HOOKED_mul;
}
