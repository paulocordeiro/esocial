package br.jus.tst.esocialjt.ocorrencia;

import br.jus.tst.esocialjt.dominio.Estado;
import br.jus.tst.esocialjt.dominio.Ocorrencia;
import br.jus.tst.esocialjt.dominio.TipoEvento;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OcorrenciaRepository extends JpaRepository<Ocorrencia, Long>, JpaSpecificationExecutor<Ocorrencia> {

    @Query(nativeQuery = true,
            value =
                    "SELECT DISTINCT(T1.COD_TIPO) "
                            + " FROM EST_EVENTO T1 "
                            + " ORDER BY 1")
    List<Long> buscarTiposEnviados();

    @Modifying
    @Query("update Ocorrencia o set o.arquivado = 'S' where o.id = ?1")
    int arquivar(Long id);

    @Modifying
    @Query("update Ocorrencia o set o.arquivado = null where o.id = ?1")
    int desarquivar(Long id);

    @Modifying
    @Query(nativeQuery = true,
            value = "UPDATE est_ocorrencia SET ind_arquivado = 'S' " +
                    "WHERE cod_ocorrencia IN (SELECT cod_ocorrencia FROM est_evento WHERE cod_estado IN (4, 5))" +
                    "AND ind_arquivado IS NULL"
    )
    int arquivarErros();

    @Modifying
    @Query("update Ocorrencia o set o.arquivado = 'S' where (o.ocorrenciaExclusaoId is not null or o.ocorrenciaRetificacaoId is not null) and o.arquivado is null")
    int arquivarExcluidosOuRetificados();

    @Query("SELECT new br.jus.tst.esocialjt.ocorrencia.OcorrenciaSumario(" +
            "o.id, " +
            "o.cpf, " +
            "o.matricula, " +
            "o.referencia, " +
            "o.evento.tipoEvento.id, " +
			"o.evento.estado.descricao, " +
            "o.dataOcorrencia " +
            ") " +
            "FROM Ocorrencia o " +
            "WHERE o.evento.tipoEvento = ?1")
    List<OcorrenciaSumario> getSumario(TipoEvento tipoEvento);

    @Query("SELECT o FROM Ocorrencia o WHERE o.evento.nrRecibo = ?1")
    List<Ocorrencia> buscarPorRecibo(String recibo);

    /**
     * Paginação por keyset: percorre as exclusões em lotes sem OFFSET, cujo custo cresce a cada
     * página. A ordenação crescente é obrigatória — é ela que faz a exclusão de maior id
     * prevalecer no desempate.
     */
    @Query("SELECT o.id FROM Ocorrencia o WHERE o.evento.estado = :estado AND o.evento.tipoEvento = :tipo AND o.id > :ultimoId ORDER BY o.id")
    List<Long> buscarIdsExclusoesAposId(
            @Param("estado") Estado estado,
            @Param("tipo") TipoEvento tipo,
            @Param("ultimoId") Long ultimoId,
            Pageable pageable
    );

    /** Projeção (id, retificarRecibo): ambos são colunas, então nenhuma entidade é materializada. */
    @Query("SELECT o.id, o.retificarRecibo FROM Ocorrencia o WHERE o.retificarRecibo IS NOT NULL AND o.evento.estado = :estado AND o.id > :ultimoId ORDER BY o.id")
    List<Object[]> buscarRetificacoesAposId(
            @Param("estado") Estado estado,
            @Param("ultimoId") Long ultimoId,
            Pageable pageable
    );

    @Query("SELECT o.id FROM Ocorrencia o WHERE o.id > :ultimoId ORDER BY o.id")
    List<Long> buscarIdsOcorrenciasAposId(@Param("ultimoId") Long ultimoId, Pageable pageable);

    /**
     * A subconsulta não é preferência de estilo: JPQL proíbe navegar por associação
     * (o.evento.nrRecibo) no WHERE de um UPDATE em massa. O filtro por estado preserva a
     * equivalência com o comportamento anterior, que só tocava alvos processados com sucesso.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Ocorrencia o SET o.ocorrenciaExclusaoId = :idExclusao WHERE o.id IN (SELECT t.id FROM Ocorrencia t WHERE t.evento.nrRecibo = :recibo AND t.evento.estado = :estado)")
    int vincularExclusao(
            @Param("idExclusao") Long idExclusao,
            @Param("recibo") String recibo,
            @Param("estado") Estado estado
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Ocorrencia o SET o.ocorrenciaRetificacaoId = :idRetificacao WHERE o.id IN (SELECT t.id FROM Ocorrencia t WHERE t.evento.nrRecibo = :recibo AND t.evento.estado = :estado)")
    int vincularRetificacao(
            @Param("idRetificacao") Long idRetificacao,
            @Param("recibo") String recibo,
            @Param("estado") Estado estado
    );

    @Query("SELECT o.id FROM Ocorrencia o WHERE " +
            "(:#{#estados.isEmpty()} = true OR o.evento.estado IN :estados) AND " +
            "(:#{#tipos.isEmpty()} = true OR o.evento.tipoEvento IN :tipos) AND " +
            "(:#{#cpfs.isEmpty()} = true OR o.cpf IN :cpfs) AND " +
            "(:#{#periodosApuracao.isEmpty()} = true OR o.periodoApuracao IN :periodosApuracao) AND " +
            "o.ocorrenciaExclusaoId IS NULL AND " +
            "o.ocorrenciaRetificacaoId IS NULL")
    List<Long> buscarIdsPorFiltros(
            @Param("estados") List<Estado> estados,
            @Param("tipos") List<TipoEvento> tipos,
            @Param("cpfs") List<String> cpfs,
            @Param("periodosApuracao") List<String> periodosApuracao
    );

    @Query("SELECT o.id FROM Ocorrencia o WHERE o.evento.estado = :estado AND o.evento.tipoEvento IN :tipos AND o.periodoApuracao IS NULL")
    List<Long> findIdsParaUpgradePeriodoApuracao(
            @Param("estado") Estado estado,
            @Param("tipos") List<TipoEvento> tipos
    );

}
