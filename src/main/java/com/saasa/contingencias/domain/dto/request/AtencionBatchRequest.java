package com.saasa.contingencias.domain.dto.request;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
public record AtencionBatchRequest(
    @NotEmpty @Size(max=50) List<@Valid AtencionRequest> pasajeros,
    Long hotelId, Long transporteId, Long restauranteId,
    RoomSelectionRequest roomSelection,
    HotelServicesRequest hotelServices,
    TransportSelectionRequest transportSelection,
    RestaurantServicesRequest restaurantServices,
    String emissionDate, String emissionPlace
) {}
