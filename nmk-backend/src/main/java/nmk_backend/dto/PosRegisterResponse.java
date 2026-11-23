package nmk_backend.dto;

import nmk_backend.entity.PosRegister;

public record PosRegisterResponse(
        Long id,
        Long locationId,
        String registerCode,
        String name
) {
    public static PosRegisterResponse from(PosRegister register) {
        return new PosRegisterResponse(
                register.getId(),
                register.getLocation().getId(),
                register.getRegisterCode(),
                register.getName()
        );
    }
}