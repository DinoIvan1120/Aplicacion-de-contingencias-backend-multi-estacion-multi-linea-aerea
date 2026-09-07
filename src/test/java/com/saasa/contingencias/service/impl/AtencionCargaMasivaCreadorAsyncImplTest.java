package com.saasa.contingencias.service.impl;

import com.saasa.contingencias.domain.dto.request.AtencionRequest;
import com.saasa.contingencias.domain.dto.request.ServicioAsignadoRequest;
import com.saasa.contingencias.domain.dto.response.AtencionResponse;
import com.saasa.contingencias.domain.enumeration.EstadoLoteEnum;
import com.saasa.contingencias.domain.model.CargaMasivaDetalle;
import com.saasa.contingencias.domain.model.CargaMasivaLote;
import com.saasa.contingencias.domain.model.RegistroVueloDiario;
import com.saasa.contingencias.domain.model.Vuelo;
import com.saasa.contingencias.domain.model.VueloRecurso;
import com.saasa.contingencias.domain.repository.CargaMasivaDetalleRepository;
import com.saasa.contingencias.service.IAtencionService;
import com.saasa.contingencias.service.ICargaMasivaProgresoService;
import com.saasa.contingencias.service.IVoucherLoteOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests unitarios de AtencionCargaMasivaCreadorAsyncImpl — la fase 1 en
 * background que reemplazó a la creación síncrona que antes vivía en
 * AtencionCargaMasivaServiceImpl.cargarRestauranteDesdeExcel.
 *
 * NOTA: igual que VoucherLoteOrchestratorImplTest, acá se llama a
 * crearAtencionesAsync(...) directamente (sin pasar por un ExecutorService
 * real) — @Async no se activa en un test unitario puro; lo que se prueba
 * es la LÓGICA de creación/agrupación, no el hilo en sí.
 */
@ExtendWith(MockitoExtension.class)
class AtencionCargaMasivaCreadorAsyncImplTest {

    @Mock ICargaMasivaProgresoService progresoService;
    @Mock CargaMasivaDetalleRepository detalleRepository;
    @Mock IAtencionService atencionService;
    @Mock IVoucherLoteOrchestrator voucherLoteOrchestrator;

    private AtencionCargaMasivaCreadorAsyncImpl creador;

    private CargaMasivaLote lote;

    @BeforeEach
    void setUp() {
        creador = new AtencionCargaMasivaCreadorAsyncImpl(
                progresoService, detalleRepository, atencionService, voucherLoteOrchestrator);

        Vuelo vuelo = Vuelo.builder().id(1L).codigoVuelo("PU302").build();
        RegistroVueloDiario registroDiario = RegistroVueloDiario.builder()
                .id(1L).active(true).vueloItinerario(vuelo).build();
        VueloRecurso restaurante = VueloRecurso.builder().id(10L).capacidadTotal(200).estado(1).build();

        lote = CargaMasivaLote.builder()
                .id(1L).loteId("lote-uuid").estado(EstadoLoteEnum.CREANDO_ATENCIONES)
                .vuelo(vuelo).registroVueloDiario(registroDiario).vueloRecurso(restaurante)
                .build();
    }

    private AtencionResponse atencionResponse(Long id, String correlativo) {
        return new AtencionResponse(id, correlativo, 1L, "PU302", "JUAN", "PEREZ", "ABC123",
                "juan@test.com", BigDecimal.ZERO, null, null, "ACTIVO", "Juan Perez",
                null, null, null, null, null, null);
    }

    @Test
    @DisplayName("Lote inexistente → se aborta sin lanzar excepción ni tocar el resto")
    void loteInexistente_seAborta() {
        when(progresoService.getLotePorLoteId("no-existe")).thenThrow(new RuntimeException("no encontrado"));

        assertDoesNotThrow(() -> creador.crearAtencionesAsync("no-existe", 1L));

        verifyNoInteractions(detalleRepository, atencionService, voucherLoteOrchestrator);
        verify(progresoService, never()).finalizarFaseCreacion(any());
    }

    @Test
    @DisplayName("Pasajero individual (grupoId null) → crea la Atención, marca PENDIENTE y asigna el servicio")
    void pasajeroIndividual_creaAtencionYAsignaServicio() {
        CargaMasivaDetalle fila = CargaMasivaDetalle.builder()
                .id(50L).grupoId(null).esTitular(true).pnr("ABC123")
                .nombre("JUAN").apellido("PEREZ").correo("juan@test.com").celular(null)
                .paxRestaurante(5).desayuno(true).almuerzo(false).cena(false)
                .build();
        when(detalleRepository.findByLote_IdOrderByIdAsc(1L)).thenReturn(List.of(fila));
        when(atencionService.create(any(), eq(1L))).thenReturn(atencionResponse(500L, "SGC-500"));
        // Primera llamada (al inicio) devuelve el lote en CREANDO_ATENCIONES; la
        // segunda (después de finalizarFaseCreacion) ya lo devuelve en PROCESANDO,
        // que es lo que decide si se dispara el envío de vouchers.
        when(progresoService.getLotePorLoteId("lote-uuid"))
                .thenReturn(lote)
                .thenReturn(CargaMasivaLote.builder().id(1L).loteId("lote-uuid")
                        .estado(EstadoLoteEnum.PROCESANDO).build());

        creador.crearAtencionesAsync("lote-uuid", 1L);

        ArgumentCaptor<AtencionRequest> reqCaptor = ArgumentCaptor.forClass(AtencionRequest.class);
        verify(atencionService).create(reqCaptor.capture(), eq(1L));
        assertEquals("JUAN", reqCaptor.getValue().nombre());
        assertNull(reqCaptor.getValue().grupoId());

        ArgumentCaptor<List<ServicioAsignadoRequest>> svCaptor = ArgumentCaptor.forClass(List.class);
        verify(atencionService).asignarServicios(eq(500L), svCaptor.capture(), eq(1L));
        assertEquals(5, svCaptor.getValue().get(0).cantidad());

        verify(progresoService).marcarAtencionesCreadasBatch(eq(1L), eq(List.of(
                new ICargaMasivaProgresoService.AtencionCreadaInfo(50L, 500L, "SGC-500"))));
        verify(progresoService, never()).marcarErrorCreacionAtencion(anyLong(), anyLong(), any());
        verify(progresoService).finalizarFaseCreacion(1L);
        verify(voucherLoteOrchestrator).procesarLoteAsync("lote-uuid", 1L);
    }

    @Test
    @DisplayName("Grupo de 3 → crea las 3 Atenciones y asigna el servicio UNA sola vez, sobre la del titular")
    void grupoDeTres_asignaServicioUnaSolaVezSobreTitular() {
        when(progresoService.getLotePorLoteId("lote-uuid")).thenReturn(lote);
        CargaMasivaDetalle titular = CargaMasivaDetalle.builder()
                .id(60L).grupoId("g1").esTitular(true).pnr("XYZ789")
                .nombre("MARIA").apellido("LOPEZ").correo("maria@test.com")
                .paxRestaurante(3).desayuno(true).almuerzo(false).cena(true)
                .build();
        CargaMasivaDetalle acomp1 = CargaMasivaDetalle.builder()
                .id(61L).grupoId("g1").esTitular(false).pnr("XYZ789")
                .nombre("CARLOS").apellido("LOPEZ").correo("maria@test.com")
                .build();
        CargaMasivaDetalle acomp2 = CargaMasivaDetalle.builder()
                .id(62L).grupoId("g1").esTitular(false).pnr("XYZ789")
                .nombre("ANA").apellido("LOPEZ").correo("maria@test.com")
                .build();
        when(detalleRepository.findByLote_IdOrderByIdAsc(1L)).thenReturn(List.of(titular, acomp1, acomp2));
        when(atencionService.create(any(), eq(1L)))
                .thenReturn(atencionResponse(601L, "SGC-601"))
                .thenReturn(atencionResponse(602L, "SGC-602"))
                .thenReturn(atencionResponse(603L, "SGC-603"));

        creador.crearAtencionesAsync("lote-uuid", 1L);

        verify(atencionService, times(3)).create(any(), eq(1L));
        // El servicio se asigna UNA sola vez, sobre la Atención de la fila titular (la primera creada: 601L)
        verify(atencionService, times(1)).asignarServicios(eq(601L), anyList(), eq(1L));
        verify(atencionService, never()).asignarServicios(eq(602L), anyList(), eq(1L));
        verify(atencionService, never()).asignarServicios(eq(603L), anyList(), eq(1L));

        verify(progresoService).marcarAtencionesCreadasBatch(eq(1L), eq(List.of(
                new ICargaMasivaProgresoService.AtencionCreadaInfo(60L, 601L, "SGC-601"),
                new ICargaMasivaProgresoService.AtencionCreadaInfo(61L, 602L, "SGC-602"),
                new ICargaMasivaProgresoService.AtencionCreadaInfo(62L, 603L, "SGC-603"))));
    }

    @Test
    @DisplayName("Falla la creación de una fila individual → se marca error y continúa con el resto")
    void fallaCreacionFilaIndividual_marcaErrorYContinua() {
        when(progresoService.getLotePorLoteId("lote-uuid")).thenReturn(lote);
        CargaMasivaDetalle filaMala = CargaMasivaDetalle.builder()
                .id(50L).grupoId(null).esTitular(true).pnr("ABC123")
                .nombre("JUAN").apellido("PEREZ").correo("juan@test.com")
                .paxRestaurante(1).build();
        CargaMasivaDetalle filaBuena = CargaMasivaDetalle.builder()
                .id(51L).grupoId(null).esTitular(true).pnr("DEF456")
                .nombre("PEDRO").apellido("RUIZ").correo("pedro@test.com")
                .paxRestaurante(1).build();
        when(detalleRepository.findByLote_IdOrderByIdAsc(1L)).thenReturn(List.of(filaMala, filaBuena));
        when(atencionService.create(argThat(r -> r != null && "ABC123".equals(r.pnr())), eq(1L)))
                .thenThrow(new RuntimeException("PNR duplicado"));
        when(atencionService.create(argThat(r -> r != null && "DEF456".equals(r.pnr())), eq(1L)))
                .thenReturn(atencionResponse(700L, "SGC-700"));

        creador.crearAtencionesAsync("lote-uuid", 1L);

        verify(progresoService).marcarErroresCreacionBatch(eq(1L), eq(List.of(
                new ICargaMasivaProgresoService.ErrorCreacionInfo(50L, "PNR duplicado"))));
        verify(progresoService).marcarAtencionesCreadasBatch(eq(1L), eq(List.of(
                new ICargaMasivaProgresoService.AtencionCreadaInfo(51L, 700L, "SGC-700"))));
        verify(progresoService).finalizarFaseCreacion(1L);
    }

    @Test
    @DisplayName("Falla la asignación del servicio del grupo → las filas creadas se marcan ERROR_CREACION, sin perder el resto del lote")
    void fallaAsignacionServicioGrupo_marcaErrorEnFilasCreadas() {
        when(progresoService.getLotePorLoteId("lote-uuid")).thenReturn(lote);
        CargaMasivaDetalle titular = CargaMasivaDetalle.builder()
                .id(60L).grupoId("g1").esTitular(true).pnr("XYZ789")
                .nombre("MARIA").apellido("LOPEZ").correo("maria@test.com")
                .paxRestaurante(3).desayuno(true).almuerzo(false).cena(true)
                .build();
        CargaMasivaDetalle acomp1 = CargaMasivaDetalle.builder()
                .id(61L).grupoId("g1").esTitular(false).pnr("XYZ789")
                .nombre("CARLOS").apellido("LOPEZ").correo("maria@test.com")
                .build();
        when(detalleRepository.findByLote_IdOrderByIdAsc(1L)).thenReturn(List.of(titular, acomp1));
        when(atencionService.create(any(), eq(1L)))
                .thenReturn(atencionResponse(601L, "SGC-601"))
                .thenReturn(atencionResponse(602L, "SGC-602"));
        when(atencionService.asignarServicios(eq(601L), anyList(), eq(1L)))
                .thenThrow(new RuntimeException("Restaurante sin cupo"));

        creador.crearAtencionesAsync("lote-uuid", 1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ICargaMasivaProgresoService.ErrorCreacionInfo>> erroresCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(progresoService).marcarErroresCreacionBatch(eq(1L), erroresCaptor.capture());
        List<ICargaMasivaProgresoService.ErrorCreacionInfo> errores = erroresCaptor.getValue();
        assertEquals(2, errores.size());
        assertEquals(60L, errores.get(0).detalleId());
        assertTrue(errores.get(0).mensajeError().contains("Restaurante sin cupo"));
        assertEquals(61L, errores.get(1).detalleId());
        verify(progresoService, never()).marcarAtencionesCreadasBatch(anyLong(), any());
    }

    @Test
    @DisplayName("Ningún pasajero se pudo crear → no dispara el envío de vouchers")
    void ningunoSeCreo_noDisparaVouchers() {
        CargaMasivaDetalle filaMala = CargaMasivaDetalle.builder()
                .id(50L).grupoId(null).esTitular(true).pnr("ABC123")
                .nombre("JUAN").apellido("PEREZ").correo("juan@test.com").paxRestaurante(1).build();
        when(detalleRepository.findByLote_IdOrderByIdAsc(1L)).thenReturn(List.of(filaMala));
        when(atencionService.create(any(), eq(1L))).thenThrow(new RuntimeException("Error"));
        when(progresoService.getLotePorLoteId("lote-uuid"))
                .thenReturn(lote)
                .thenReturn(CargaMasivaLote.builder().id(1L).loteId("lote-uuid")
                        .estado(EstadoLoteEnum.ERROR_CREACION).build());

        creador.crearAtencionesAsync("lote-uuid", 1L);

        verify(progresoService).finalizarFaseCreacion(1L);
        verifyNoInteractions(voucherLoteOrchestrator);
    }

    @Test
    @DisplayName("Mensaje de error nulo → usa mensaje por defecto en vez de null")
    void mensajeErrorNulo_usaMensajePorDefecto() {
        when(progresoService.getLotePorLoteId("lote-uuid")).thenReturn(lote);
        CargaMasivaDetalle fila = CargaMasivaDetalle.builder()
                .id(50L).grupoId(null).esTitular(true).pnr("ABC123")
                .nombre("JUAN").apellido("PEREZ").correo("juan@test.com").paxRestaurante(1).build();
        when(detalleRepository.findByLote_IdOrderByIdAsc(1L)).thenReturn(List.of(fila));
        when(atencionService.create(any(), eq(1L))).thenThrow(new RuntimeException((String) null));

        creador.crearAtencionesAsync("lote-uuid", 1L);

        verify(progresoService).marcarErroresCreacionBatch(eq(1L), eq(List.of(
                new ICargaMasivaProgresoService.ErrorCreacionInfo(
                        50L, "Error desconocido al crear la atención"))));
    }

    @Test
    @DisplayName("Más de CHUNK_SIZE (25) filas → el progreso se vuelca en varios chunks, no en una sola llamada al final")
    void masDeChunkSize_vuelcaProgresoEnVariosChunks() {
        when(progresoService.getLotePorLoteId("lote-uuid")).thenReturn(lote);

        List<CargaMasivaDetalle> filas = new java.util.ArrayList<>();
        for (long i = 1; i <= 30; i++) {
            filas.add(CargaMasivaDetalle.builder()
                    .id(i).grupoId(null).esTitular(true).pnr("PNR" + i)
                    .nombre("PAX" + i).apellido("TEST").correo("pax" + i + "@test.com")
                    .paxRestaurante(1).build());
        }
        when(detalleRepository.findByLote_IdOrderByIdAsc(1L)).thenReturn(filas);

        List<AtencionResponse> respuestas = new java.util.ArrayList<>();
        for (long i = 1; i <= 30; i++) {
            respuestas.add(atencionResponse(1000L + i, "SGC-" + i));
        }
        org.mockito.stubbing.OngoingStubbing<AtencionResponse> stub =
                when(atencionService.create(any(), eq(1L)));
        for (AtencionResponse r : respuestas) {
            stub = stub.thenReturn(r);
        }

        creador.crearAtencionesAsync("lote-uuid", 1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ICargaMasivaProgresoService.AtencionCreadaInfo>> chunkCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(progresoService, times(2)).marcarAtencionesCreadasBatch(eq(1L), chunkCaptor.capture());

        List<List<ICargaMasivaProgresoService.AtencionCreadaInfo>> chunks = chunkCaptor.getAllValues();
        assertEquals(25, chunks.get(0).size(), "El primer chunk debe volcarse apenas llega a CHUNK_SIZE (25)");
        assertEquals(5, chunks.get(1).size(), "El segundo chunk (resto) se vuelca en el flush final");

        verify(progresoService, never()).marcarErroresCreacionBatch(anyLong(), any());
    }
}

