package br.jus.tst.esocialjt.auto.tarefas;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.transaction.Transactional;

import br.jus.tst.esocialjt.dominio.GrupoTipoEvento;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import br.jus.tst.esocialjt.dominio.EnvioEvento;
import br.jus.tst.esocialjt.dominio.Estado;
import br.jus.tst.esocialjt.dominio.Evento;
import br.jus.tst.esocialjt.evento.EventoDTO;
import br.jus.tst.esocialjt.negocio.EnvioServico;
import br.jus.tst.esocialjt.negocio.EventoServico;
import br.jus.tst.esocialjt.regras.evento.RegrasFactory;

@Service
@Transactional
public class TarefaEnvioEventos implements Tarefa {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(TarefaEnvioEventos.class);

	@Autowired
	private EnvioServico envioServico;
	
	@Autowired
	private EventoServico eventoServico;
	
	@Autowired
	private RegrasFactory regrasFactory;
	
	@Value("${esocialjt.limite-eventos-por-lote: 50}")
	private Long LIMITE_EVENTOS_LOTE;
	
	@Value("${esocialjt.lotes-por-ciclo: 10}")
	private Long lotesPorCiclo;

	@Override
	public boolean executar() {
		List<Evento> eventos = selecionarEventosParaEnvio();	
		if(!eventos.isEmpty()) {
			LOGGER.info("Enviando " + eventos.size() + " eventos para o eSocial-GOV");
			List<EnvioEvento> envios = envioServico.enviarEventosParaESocialGov(eventos);
			logResultado(envios);
		}
		return true;
	}
	
	private List<Evento> selecionarEventosParaEnvio() {
		List<EventoDTO> eventosEmFila = eventoServico
				.criarConsulta()
				.nosEstados(Estado.EM_FILA)
				.buscarDTO();
		
		if(!eventosEmFila.isEmpty()) {
			LOGGER.info(eventosEmFila.size() + " eventos na fila");
		}
		
		// IMPORTANTE: filtrar por 'habilitado' ANTES de limitar. Se limitarmos primeiro,
		// eventos bloqueados no topo da fila (ex.: periódicos aguardando não-periódicos)
		// ocupam toda a janela e congelam a fila, mesmo havendo eventos habilitados atrás.
		List<EventoDTO> eventosHabilitados = eventosEmFila.stream()
			.filter(evento -> regrasFactory.getRegra(evento).habilitado(evento))
			.limit(LIMITE_EVENTOS_LOTE * lotesPorCiclo)
			.collect(Collectors.toList());
		
		// Verifica se algum evento é do grupo TABELA
		boolean temEventoTabela = eventosHabilitados.stream()
			.anyMatch(evento ->
					evento.getCodGrupoEvento() != null &&
							evento.getCodGrupoEvento().equals(GrupoTipoEvento.TABELA.getId())
			);

		// Se tem evento de tabela, limita a apenas 1 lote
		long limite = temEventoTabela ? LIMITE_EVENTOS_LOTE : (LIMITE_EVENTOS_LOTE * lotesPorCiclo);

		if(temEventoTabela) {
			LOGGER.info("Eventos de TABELA detectados. Limitando envio a 1 lote");
		}

		List<EventoDTO> eventosParaEnviar = eventosHabilitados.stream()
			.limit(limite)
			.collect(Collectors.toList());

		Long[] ids = eventosParaEnviar
			.stream()
			.map(EventoDTO::getId)
			.toArray(Long[]::new);
		
		List<Evento> eventos = Collections.emptyList();
		
		if(ids.length > 0) {
			eventos = eventoServico
					.criarConsulta()
					.comIds(ids)
					.buscar();
		}
		
		return eventos;
	}
	

	private void logResultado(List<EnvioEvento> envios) {
		Map<String, Long> resultado = envios.stream()
			.map(EnvioEvento::getEvento)
			.map(Evento::getEstado)
			.collect(Collectors.groupingBy(Estado::getDescricao, Collectors.counting()));
		String info = String.format("Eventos enviados: %s", resultado);
		LOGGER.info(info);
	}
}
