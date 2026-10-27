package com.omnihub.iot.system;

import com.omnihub.iot.common.Jsons;
import com.omnihub.iot.common.SensitiveMasker;
import com.omnihub.iot.security.LoginUser;
import com.omnihub.iot.system.entity.OperationLog;
import com.omnihub.iot.system.mapper.OperationLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Writes the operation audit trail. Arguments are masked (passwords / secrets / tokens) and truncated. */
@Aspect
@Component
public class OpLogAspect {

    private static final Logger log = LoggerFactory.getLogger(OpLogAspect.class);

    private final OperationLogMapper mapper;

    public OpLogAspect(OperationLogMapper mapper) {
        this.mapper = mapper;
    }

    @Around("@annotation(opLog)")
    public Object around(ProceedingJoinPoint pjp, OpLog opLog) throws Throwable {
        long start = System.currentTimeMillis();
        boolean ok = true;
        String error = null;
        try {
            return pjp.proceed();
        } catch (Throwable t) {
            ok = false;
            error = t.getMessage();
            throw t;
        } finally {
            save(pjp, opLog, ok, error, System.currentTimeMillis() - start);
        }
    }

    private void save(ProceedingJoinPoint pjp, OpLog opLog, boolean ok, String error, long cost) {
        try {
            OperationLog l = new OperationLog();
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof LoginUser u) {
                l.setUsername(u.username());
                l.setTenantId(u.tenantId());
            }
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes sra) {
                HttpServletRequest req = sra.getRequest();
                l.setMethod(req.getMethod());
                l.setPath(req.getRequestURI());
                l.setIp(req.getRemoteAddr());
            }
            l.setModule(opLog.module());
            l.setAction(opLog.action());
            l.setSuccess(ok);
            l.setCostMs(cost);
            l.setDetail(detail(pjp.getArgs(), error));
            l.setCreatedAt(Instant.now());
            mapper.insert(l);
        } catch (Exception e) {
            log.warn("Failed to write operation log: {}", e.getMessage());
        }
    }

    private static String detail(Object[] args, String error) {
        List<Object> printable = new ArrayList<>();
        for (Object a : args) {
            if (a == null || a instanceof HttpServletRequest) {
                continue;
            }
            printable.add(a);
        }
        String json;
        try {
            json = Jsons.toJson(printable);
        } catch (Exception e) {
            json = "[unserializable]";
        }
        String d = SensitiveMasker.maskJson(json);
        if (error != null) {
            d += " | error=" + error;
        }
        return SensitiveMasker.truncate(d, 900);
    }
}
