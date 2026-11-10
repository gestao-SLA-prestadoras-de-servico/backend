-- Política vigente do contrato (RF-08). Coluna, não tabela N:N: o contrato tem no máximo uma política por vez (RN-02).
-- Nula enquanto o gestor não vincula; contrato sem política não recebe chamados (RN-03). O contrato aponta para a
-- política, e o chamado guarda a versão usada na abertura (RN-05).
ALTER TABLE contrato ADD COLUMN politica_id uuid REFERENCES politica_sla (id);

CREATE INDEX contrato_politica_id_idx ON contrato (politica_id);
